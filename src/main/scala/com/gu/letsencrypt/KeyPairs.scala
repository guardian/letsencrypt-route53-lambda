package com.gu.letsencrypt

import java.io.{ByteArrayInputStream, FileReader, FileWriter}
import java.security.KeyPair

import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest, S3Object}
import dumpa.Dumpa.AWSCredentialsProvider
import org.shredzone.acme4j.util.KeyPairUtils
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions.EU_WEST_1

object KeyPairs {

  // RSA key size of generated key pairs
  val KEY_SIZE = 2048


  val s3Client: AmazonS3Client = {
    val c = new AmazonS3Client(AWSCredentialsProvider.Chain)
    c.setRegion(Region getRegion EU_WEST_1)
    c
  }

  /**
    * Loads a domain key pair from {@value #DOMAIN_KEY_FILE}. If the file does not exist,
    * a new key pair is generated and saved.
    */
  def loadOrCreateDomainKeyPair():KeyPair= {
    // s3Client.getObject("bucket", "path")
    // KeyPairUtils.readKeyPair(???)


    val domainKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE)

    // KeyPairUtils.writeKeyPair(domainKeyPair, ???)
    domainKeyPair
  }


  /**
    * Loads a user key pair from {@value #USER_KEY_FILE}. If the file does not exist,
    * a new key pair is generated and saved.
    * <p>
    * Keep this key pair in a safe place! In a production environment, you will not be
    * able to access your account again if you should lose the key pair.
    *
    */
  def  loadOrCreateUserKeyPair(): KeyPair = {
    KeyPairUtils.createKeyPair(KEY_SIZE)
  }
}
