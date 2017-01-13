package com.gu.letsencrypt.route53

import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.ChangeAction.UPSERT
import com.amazonaws.services.route53.model.ChangeStatus.INSYNC
import com.amazonaws.services.route53.model.RRType.TXT
import com.amazonaws.services.route53.model._
import com.gu.letsencrypt.KeyPairs
import com.gu.util.Retry
import dumpa.Dumpa.AWSCredentialsProvider
import org.shredzone.acme4j.Status.VALID
import org.shredzone.acme4j.challenge.{Challenge, Dns01Challenge}
import org.shredzone.acme4j.exception.{AcmeConflictException, AcmeException}
import org.shredzone.acme4j.{Registration, RegistrationBuilder, Session}
import org.slf4j.LoggerFactory

import scala.collection.convert.wrapAll._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Authorization {
  val logger = LoggerFactory.getLogger("Authorization")

  val route53Client = new AmazonRoute53Client(AWSCredentialsProvider.Chain)

  implicit class RichChallenge(challenge: Challenge) {
    def hasBeenValidated(): Boolean = challenge.getStatus == VALID
  }

  def registerWithAcmeServerAndAuthoriseDomains(domains: Set[String]): Future[Registration] = {
    logger.info(s"Starting registerWithAcmeServerAndAuthoriseDomains $domains")
    val hostedIdsF = fetchHostedIds()
    val registrationF = getAcmeRegistration
    for {
      reg <- registrationF
      _ <- acquireAndFulfillChallengesFor(domains, reg, hostedIdsF)
    } yield reg
  }

  def fulfill(hostedZone: HostedZone, challengesByDomain: Map[String, Dns01Challenge]): Future[_] = {
    val changes = for {
      (domain, challenge) <- challengesByDomain if !challenge.hasBeenValidated()
    } yield new Change().withAction(UPSERT).withResourceRecordSet(
      new ResourceRecordSet()
        .withName(s"_acme-challenge.$domain.")
        .withType(TXT)
        .withTTL(1L)
        .withResourceRecords(new ResourceRecord().withValue(s""""${challenge.getDigest}""""))
    )
    logger.info(s"*** Changes: ${changes.mkString(",")}")

    val changeId = route53Client.changeResourceRecordSets(
      new ChangeResourceRecordSetsRequest().withHostedZoneId(hostedZone.getId).withChangeBatch(
        new ChangeBatch().withChanges(changes)
      )
    ).getChangeInfo.getId

    val changesSynchronisedF = Retry.retry(
      s"Check Route53 update for ${hostedZone.getId} / ${hostedZone.getName}",
      Future(route53Client.getChange(new GetChangeRequest().withId(changeId))),
      Retry.Delays.fibonacci)(_.getChangeInfo.getStatus == INSYNC.name())
    for {
      _ <- changesSynchronisedF
      _ <- Future.traverse(challengesByDomain) { case (domain, challenge) =>
        tellAcmeServerTheChallengeIsReadyToBeCheckedAndWaitForValidation(domain, challenge)
      }
    } yield {
      logger.info(s"*** Fulfilled auth for all domains: ${challengesByDomain.keys.mkString(",")}")
      Unit
    }
  }

  def acquireAndFulfillChallengesFor(domains: Set[String], reg: Registration, hostedIdsF: Future[Map[String, HostedZone]]): Future[Unit] = {
    val challengeFuturesByDomain = domains.map(domain => domain -> acquireChallengeForDomain(reg, domain)).toMap
    for {
      hostedIds <- hostedIdsF
      _ <- fulfillAll(challengeFuturesByDomain, hostedIds)
    } yield {}
  }

  def fulfillAll(challengeFuturesByDomain: Map[String, Future[Dns01Challenge]], hostedZonesByName: Map[String, HostedZone]) = Future.sequence(for {
    (hostedZone, challengesByDomainF) <- challengesByDomainPerHostedZone(challengeFuturesByDomain, hostedZonesByName)
  } yield for {
    challengesByDomain <- challengesByDomainF
    _ <- fulfill(hostedZone, challengesByDomain)
  } yield {})

  def challengesByDomainPerHostedZone(
    challengeFuturesByDomain: Map[String, Future[Dns01Challenge]],
    hostedZonesByName: Map[String, HostedZone]
  ): Map[HostedZone, Future[Map[String, Dns01Challenge]]] = {
    challengeFuturesByDomain.groupBy {
      case (domain, _) => hostedZonesByName.find {
        case (domainZone, hostedZone) => domain.endsWith(domainZone.stripSuffix("."))
      }.get._2
    }.mapValues(futureOfMapWithFutureValues)
  }

  def getAcmeRegistration: Future[Registration] = Future {
    // Load the user key file. If there is no key file, create a new one.
    val userKeyPair = KeyPairs.loadOrCreateUserKeyPair()

    // Create a session for Let's Encrypt.
    // Use "acme://letsencrypt.org" for production server
    val session = new Session("acme://letsencrypt.org/staging", userKeyPair)

    // Get the Registration to the account.
    // If there is no account yet, create a new one.
    findOrRegisterAccount(session)
  }


  def futureOfMapWithFutureValues[K, V](mapWithFutureValues: Map[K, Future[V]]): Future[Map[K, V]] = Future.sequence(for {
    (k, vF) <- mapWithFutureValues
  } yield for {
    v <- vF
  } yield k -> v).map(_.toMap)

  def acquireChallengeForDomain(reg: Registration, domain: String): Future[Dns01Challenge] = Future {
    val auth = reg.synchronized { // acme4j is really-really-not-threadsafe
      reg.authorizeDomain(domain)
    }
    logger.info("Authorization for domain " + domain)
    val challenge: Dns01Challenge = auth.findChallenge(Dns01Challenge.TYPE)
    if (challenge == null) {
      throw new AcmeException("Found no " + Dns01Challenge.TYPE + " challenge, don't know what to do...")
    }

    challenge
  }


  def tellAcmeServerTheChallengeIsReadyToBeCheckedAndWaitForValidation(domain: String, challenge: Challenge): Future[_] = {
    // Now trigger the challenge.
    challenge.trigger()

    Retry.retry(
      s"Check challenge on $domain is validated",
      Future {
        challenge.update() // acme4j has Challenge as a mutable object
        challenge
      },
      Retry.Delays.fibonacci) { ch =>
      val hasBeenValidated = ch.hasBeenValidated()
      logger.info(s"Challenge for $domain : ${challenge.getStatus}")
      hasBeenValidated
    } // also check for INVALID, and die?
  }

  //  def testDNS() {
  //
  //    val testDNSResult: TestDNSAnswerResult = route53Client.testDNSAnswer(
  //      new TestDNSAnswerRequest()
  //        .withHostedZoneId(hostedZoneId)
  //        .withRecordName(recordName)
  //        .withRecordType(TXT)
  //    )
  //    testDNSResult.getResponseCode // NOERROR
  //    testDNSResult.getRecordData
  //
  //  }

  def fetchHostedIds(): Future[Map[String, HostedZone]] = Future {
    val hostedZones = route53Client.listHostedZones().getHostedZones
    val m = hostedZones.map(hostedZone => hostedZone.getName -> hostedZone).toMap
    logger.info(s"Fetched Hosted Zones :\n${m.mapValues(_.getId).mkString("\n")}")
    m
  }


  /**
    * Finds your {@link Registration} at the ACME server. It will be found by your user's
    * public key. If your key is not known to the server yet, a new registration will be
    * created.
    * <p>
    * This is a simple way of finding your {@link Registration}. A better way is to get
    * the URI of your new registration with {@link Registration#getLocation()} and store
    * it somewhere. If you need to get access to your account later, reconnect to it via
    * {@link Registration#bind(Session, URI)} by using the stored location.
    */
  def findOrRegisterAccount(session: Session): Registration = {
    try {
      // Try to create a new Registration.
      val reg = new RegistrationBuilder().create(session)
      logger.info("Registered a new user, URI: " + reg.getLocation)

      // This is a new account. Let the user accept the Terms of Service.
      // We won't be able to authorize domains until the ToS is accepted.
      val agreement = reg.getAgreement()
      logger.info("Terms of Service: " + agreement)
      reg.modify().setAgreement(agreement).commit()

      reg

    } catch {
      case ex: AcmeConflictException =>
        // The Key Pair is already registered. getLocation() contains the
        // URL of the existing registration's location. Bind it to the session.
        val reg = Registration.bind(session, ex.getLocation)
        logger.info("Account does already exist, URI: " + reg.getLocation, ex)
        reg
    }
  }

}
