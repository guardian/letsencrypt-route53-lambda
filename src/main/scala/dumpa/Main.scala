package dumpa

import java.io._
import java.time.Instant

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.S3ObjectId
import com.gu.letsencrypt.KeyPairs
import com.gu.letsencrypt.route53.Authorization.registerWithAcmeServerAndAuthoriseDomains
import org.shredzone.acme4j._
import org.shredzone.acme4j.util.CSRBuilder
import org.slf4j.LoggerFactory

import scala.collection.convert.wrapAll._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object LetsEncrypt {

  val STAGING    = "https://acme-staging.api.letsencrypt.org/"
  val PRODUCTION = "https://acme-v01.api.letsencrypt.org/"

}

case class ScheduledEvent(
  account: String,
  region: String,
  `detail-type`: String,
  source: String,
  time: Instant,
  id: String,
  resources: Seq[String]
)

/**
  * Load AWS hosted zone information, to map between domains and hosted zone ids
  * Load-Create User-Key pair
  * Create session (needs user-key)
  * Register
  * Authorize for all domains:
  *   + per-domain: [hit ACME for all challenges]
  *   + per-hosted-zone: [create a bulk AWS domain update, monitor for change done]
  *   + per-domain: [test domain in AWS, hit acme to validate challenge]
  * Generate a CSR: [Load-Create Domain-Key pair, add and sign domains]
  * Hit ACME for signing of cert
  * Download [certificate, chain]
  * writeX509CertificateChain to S3
  */
object Dumpa extends App {
  val logger = LoggerFactory.getLogger("Dumpa")


  object AWSCredentialsProvider {
    val Dev = new ProfileCredentialsProvider("membership")
    val Prod = new InstanceProfileCredentialsProvider(false)
    val Chain = new AWSCredentialsProviderChain(Dev, Prod)
  }

  val s3Client: AmazonS3Client = {
    val c = new AmazonS3Client(AWSCredentialsProvider.Chain)
    c.setRegion(Region getRegion EU_WEST_1)
    c
  }

  /**
    * Generates a certificate for the given domains. Also takes care for the registration
    * process.
    *
    * @param domains
    *            Domains to get a common certificate for
    */
  def fetchCertificate(domains: Set[String]) = {
    val regF = registerWithAcmeServerAndAuthoriseDomains(domains)
    val csrF = generateCertificateSigningRequest(domains)
    for {
      reg <- regF
      csr <- csrF
    } yield {
      askLetsEncryptToSignOurCertificateAndThenStoreIt(csr, reg)
    }
  }

  def generateCertificateSigningRequest(domains: Set[String]): Future[CSRBuilder] = Future {
    // Load or create a key pair for the domains. This should not be the userKeyPair!
    val domainKeyPair = KeyPairs.loadOrCreateDomainKeyPair()

    // Generate a CSR for all of the domains, and sign it with the domain key pair.
    val csrb = new CSRBuilder()
    csrb.addDomains(domains)
    csrb.sign(domainKeyPair)

    // Write the CSR to a file, for later use.
//    try (Writer out = new FileWriter(DOMAIN_CSR_FILE)) {
//      csrb.write(out)
//    }

    csrb
  }

  def askLetsEncryptToSignOurCertificateAndThenStoreIt(csrb: CSRBuilder, reg: Registration) {
    // Now request a signed certificate.
    val certificate = reg.requestCertificate(csrb.getEncoded)

    logger.info("Certificate URI: " + certificate.getLocation)

    // Download the leaf certificate and certificate chain.
    val cert = certificate.download()
    val chain = certificate.downloadChain()

    // Write a combined file containing the certificate and chain.
//    try (FileWriter fw = new FileWriter(DOMAIN_CHAIN_FILE)) {
//      CertificateUtils.writeX509CertificateChain(fw, cert, chain)
//    }

    // That's all! Configure your web server to use the DOMAIN_KEY_FILE and
    // DOMAIN_CHAIN_FILE for the requested domans.
  }

//  def wack(date: LocalDate): PutObjectResult = {
//
//    println(s"doing $date")
//
//      val os = new ByteArrayOutputStream()
//
//
//      val contentBytes: Array[Byte] = os.toByteArray
//
//      val is = new ByteArrayInputStream(contentBytes)
//      val metadata: ObjectMetadata = new ObjectMetadata
//      metadata.setContentType("text/plain")
//      metadata.setContentLength(contentBytes.length)
//
//      s3Client.putObject(new PutObjectRequest("roberto-is-just-trying-things-august-2016", s"bang/date=$date/data.csv", is, metadata))
//  }

  logger.info("Foo "+Await.result(fetchCertificate(Set("roberto-test.cas-beta.guardianapis.com")), 300.seconds))
}

case class Conf(
  domains: Set[String],
  userKeyS3: S3ObjectId, // private key used to authenticate with the ACME server
  domainKeyS3: S3ObjectId, // private key used with the SSL certificate
  certificateS3: S3ObjectId // public certificate
)

class Dumpa {


  def time(in: InputStream, out: OutputStream, context: Context): Unit = {
    Dumpa.fetchCertificate(Set("roberto-test.cas-beta.guardianapis.com"))
  }

}