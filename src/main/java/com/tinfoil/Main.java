package com.tinfoil;

import software.amazon.encryption.s3.S3EncryptionClient;

public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, world!");
        System.out.println("S3 encryption client on classpath: " + S3EncryptionClient.class.getName());
    }
}
