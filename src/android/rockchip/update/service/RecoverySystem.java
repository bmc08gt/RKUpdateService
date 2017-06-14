package android.rockchip.update.service;

/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
//import java.security.cert.X509Certificate;
import java.security.cert.X509Certificate;

import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.os.PowerManager;
import android.content.Context;
import android.util.Log;

import org.apache.harmony.security.asn1.BerInputStream;
import org.apache.harmony.security.pkcs7.ContentInfo;
import org.apache.harmony.security.pkcs7.SignedData;
import org.apache.harmony.security.pkcs7.SignerInfo;
//import org.apache.harmony.security.provider.cert.X509CertImpl;
/**
 * RecoverySystem contains methods for interacting with the Android
 * recovery system (the separate partition that can be used to install
 * system updates, wipe user data, etc.)
 */
public class RecoverySystem {
    private static final String TAG = "RecoverySystem";

    /**
     * Default location of zip file containing public keys (X509
     * certs) authorized to sign OTA updates.
     */
    private static final File DEFAULT_KEYSTORE =
        new File("/system/etc/security/otacerts.zip");

    /** Send progress to listeners no more often than this (in ms). */
    private static final long PUBLISH_PROGRESS_INTERVAL_MS = 500;

    /** Used to communicate with recovery.  See bootable/recovery/recovery.c. */
    private static File RECOVERY_DIR = new File("/cache/recovery");
    private static File UPDATE_FLAG_FILE = new File(RECOVERY_DIR, "last_flag");
    private static File COMMAND_FILE = new File(RECOVERY_DIR, "command");
    private static File LOG_FILE = new File(RECOVERY_DIR, "log");
    private static String LAST_PREFIX = "last_";

    // Length limits for reading files.
    private static int LOG_FILE_MAX_LENGTH = 64 * 1024;

    /**
     * Interface definition for a callback to be invoked regularly as
     * verification proceeds.
     */
    public interface ProgressListener {
        /**
         * Called periodically as the verification progresses.
         *
         * @param progress  the approximate percentage of the
         *        verification that has been completed, ranging from 0
         *        to 100 (inclusive).
         */
        public void onProgress(int progress);
    }

    /** @return the set of certs that can be used to sign an OTA package. */
    private static HashSet<Certificate> getTrustedCerts(File keystore)
        throws IOException, GeneralSecurityException {
        HashSet<Certificate> trusted = new HashSet();
        if (keystore == null) {
            keystore = DEFAULT_KEYSTORE;
        }
        ZipFile zip = new ZipFile(keystore);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                trusted.add(cf.generateCertificate(zip.getInputStream(entry)));
            }
        } finally {
            zip.close();
        }
        return trusted;
    }

    /**
     * Verify the cryptographic signature of a system update package
     * before installing it.  Note that the package is also verified
     * separately by the installer once the device is rebooted into
     * the recovery system.  This function will return only if the
     * package was successfully verified; otherwise it will throw an
     * exception.
     *
     * Verification of a package can take significant time, so this
     * function should not be called from a UI thread.  Interrupting
     * the thread while this function is in progress will result in a
     * SecurityException being thrown (and the thread's interrupt flag
     * will be cleared).
     *
     * @param packageFile  the package to be verified
     * @param listener     an object to receive periodic progress
     * updates as verification proceeds.  May be null.
     * @param deviceCertsZipFile  the zip file of certificates whose
     * public keys we will accept.  Verification succeeds if the
     * package is signed by the private key corresponding to any
     * public key in this file.  May be null to use the system default
     * file (currently "/system/etc/security/otacerts.zip").
     *
     * @throws IOException if there were any errors reading the
     * package or certs files.
     * @throws GeneralSecurityException if verification failed
     */
    public static void verifyPackage(File packageFile,
                                     ProgressListener listener,
                                     File deviceCertsZipFile)
        throws IOException, GeneralSecurityException {
	/*long fileLen = packageFile.length();

        RandomAccessFile raf = new RandomAccessFile(packageFile, "r");
        try {
            int lastPercent = 0;
            long lastPublishTime = System.currentTimeMillis();
            if (listener != null) {
                listener.onProgress(lastPercent);
            }

            raf.seek(fileLen - 6);
            byte[] footer = new byte[6];
            raf.readFully(footer);

            if (footer[2] != (byte)0xff || footer[3] != (byte)0xff) {
                throw new SignatureException("no signature in file (no footer)");
            }

            int commentSize = (footer[4] & 0xff) | ((footer[5] & 0xff) << 8);
            int signatureStart = (footer[0] & 0xff) | ((footer[1] & 0xff) << 8);
            Log.v(TAG, String.format("comment size %d; signature start %d",
                                     commentSize, signatureStart));

            byte[] eocd = new byte[commentSize + 22];
            raf.seek(fileLen - (commentSize + 22));
            raf.readFully(eocd);

            // Check that we have found the start of the
            // end-of-central-directory record.
            if (eocd[0] != (byte)0x50 || eocd[1] != (byte)0x4b ||
                eocd[2] != (byte)0x05 || eocd[3] != (byte)0x06) {
                throw new SignatureException("no signature in file (bad footer)");
            }

            for (int i = 4; i < eocd.length-3; ++i) {
                if (eocd[i  ] == (byte)0x50 && eocd[i+1] == (byte)0x4b &&
                    eocd[i+2] == (byte)0x05 && eocd[i+3] == (byte)0x06) {
                    throw new SignatureException("EOCD marker found after start of EOCD");
                }
            }

            // The following code is largely copied from
            // JarUtils.verifySignature().  We could just *call* that
            // method here if that function didn't read the entire
            // input (ie, the whole OTA package) into memory just to
            // compute its message digest.

            BerInputStream bis = new BerInputStream(
                new ByteArrayInputStream(eocd, commentSize+22-signatureStart, signatureStart));
            ContentInfo info = (ContentInfo)ContentInfo.ASN1.decode(bis);
            SignedData signedData = info.getSignedData();
            if (signedData == null) {
                throw new IOException("signedData is null");
            }
            Collection encCerts = signedData.getCertificates();
            if (encCerts.isEmpty()) {
                throw new IOException("encCerts is empty");
            }
            // Take the first certificate from the signature (packages
            // should contain only one).
            Iterator it = encCerts.iterator();
            X509Certificate cert = null;
            if (it.hasNext()) {
                //cert = Certificate.getInstance((org.apache.harmony.security.x509.Certificate)it.next());
                CertificateFactory cf = CertificateFactory.getInstance("X.509");	
		ByteArrayInputStream stream = new ByteArrayInputStream(((org.apache.harmony.security.x509.Certificate)it.next()).toString().getBytes("UTF_8"));
		cert = (X509Certificate)cf.generateCertificate(stream);
		//cert = (X509Certificate) cf.generateCertificate((org.apache.harmony.security.x509.Certificate)it.next().toString());
	    } else {
                throw new SignatureException("signature contains no certificates");
            }

            List sigInfos = signedData.getSignerInfos();
            SignerInfo sigInfo;
            if (!sigInfos.isEmpty()) {
                sigInfo = (SignerInfo)sigInfos.get(0);
            } else {
                throw new IOException("no signer infos!");
            }

            // Check that the public key of the certificate contained
            // in the package equals one of our trusted public keys.

            HashSet<Certificate> trusted = getTrustedCerts(
                deviceCertsZipFile == null ? DEFAULT_KEYSTORE : deviceCertsZipFile);

            PublicKey signatureKey = cert.getPublicKey();
            boolean verified = false;
            for (Certificate c : trusted) {
                if (c.getPublicKey().equals(signatureKey)) {
                    verified = true;
                    break;
                }
            }
            if (!verified) {
                throw new SignatureException("signature doesn't match any trusted key");
            }

            // The signature cert matches a trusted key.  Now verify that
            // the digest in the cert matches the actual file data.

            // The verifier in recovery *only* handles SHA1withRSA
            // signatures.  SignApk.java always uses SHA1withRSA, no
            // matter what the cert says to use.  Ignore
            // cert.getSigAlgName(), and instead use whatever
            // algorithm is used by the signature (which should be
            // SHA1withRSA).

            String da = sigInfo.getDigestAlgorithm();
            String dea = sigInfo.getDigestEncryptionAlgorithm();
            String alg = null;
            if (da == null || dea == null) {
                // fall back to the cert algorithm if the sig one
                // doesn't look right.
                alg = cert.getSigAlgName();
            } else {
                alg = da + "with" + dea;
            }
            Signature sig = Signature.getInstance(alg);
            sig.initVerify(cert);

            // The signature covers all of the OTA package except the
            // archive comment and its 2-byte length.
            long toRead = fileLen - commentSize - 2;
            long soFar = 0;
            raf.seek(0);
            byte[] buffer = new byte[4096];
            boolean interrupted = false;
            while (soFar < toRead) {
                interrupted = Thread.interrupted();
                if (interrupted) break;
                int size = buffer.length;
                if (soFar + size > toRead) {
                    size = (int)(toRead - soFar);
                }
                int read = raf.read(buffer, 0, size);
                sig.update(buffer, 0, read);
                soFar += read;

                if (listener != null) {
                    long now = System.currentTimeMillis();
                    int p = (int)(soFar * 100 / toRead);
                    if (p > lastPercent &&
                        now - lastPublishTime > PUBLISH_PROGRESS_INTERVAL_MS) {
                        lastPercent = p;
                        lastPublishTime = now;
                        listener.onProgress(lastPercent);
                    }
                }
            }
            if (listener != null) {
                listener.onProgress(100);
            }

            if (interrupted) {
                throw new SignatureException("verification was interrupted");
            }

            if (!sig.verify(sigInfo.getEncryptedDigest())) {
                throw new SignatureException("signature digest verification failed");
            }
        } finally {
            raf.close();
        }
	*/
    }

    /**
     * Reboots the device in order to install the given update
     * package.
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     *
     * @param context      the Context to use
     * @param packageFile  the update package to install.  Currently
     * must be on the /cache or /data partitions.
     *
     * @throws IOException  if writing the recovery command file
     * fails, or if the reboot itself fails.
     */
    public static void installPackage(Context context, File packageFile)
        throws IOException {
        String filename = packageFile.getPath();

        Log.w(TAG, "!!! REBOOTING TO INSTALL " + filename + " !!!");
        String arg = "--update_package=" + filename;
        writeFlagCommand(filename);
        bootCommand(context, arg);
    }

    public static void installRKimage(Context context, String imagePath)
        throws IOException {

        Log.w(TAG, "!!! REBOOTING TO INSTALL rkimage " + imagePath + " !!!");
        String arg = "--update_rkimage=" + imagePath;
        writeFlagCommand(imagePath);
        bootCommand(context, arg);
    }

    public static String readFlagCommand() {
        if (UPDATE_FLAG_FILE.exists()) {
            char[] buf = new char[128];
            int readCount = 0;
            try {
                FileReader reader = new FileReader(UPDATE_FLAG_FILE);
                readCount = reader.read(buf, 0, buf.length);
                Log.d(TAG, "readCount = " + readCount + " buf.length = " + buf.length);
            } catch (IOException e) {
                Log.e(TAG, "can not read /cache/recovery/flag!");
            } finally {
                UPDATE_FLAG_FILE.delete();
            }
            StringBuilder sBuilder = new StringBuilder();
            for (int i = 0; i < readCount; i++) {
                if (buf[i] == 0) break;
                sBuilder.append(buf[i]);
            }
            return sBuilder.toString();
        }
        return null;
    }

    public static void writeFlagCommand(String path)
        throws IOException {

        RECOVERY_DIR.mkdirs();
        UPDATE_FLAG_FILE.delete();
        FileWriter writer = new FileWriter(UPDATE_FLAG_FILE);
        try {
            writer.write("updating$path=" + path);
        } finally {
            writer.close();
        }
    }

    /**
     * Reboot into the recovery system with the supplied argument.
     * @param arg to pass to the recovery utility.
     * @throws IOException if something goes wrong.
     */
    private static void bootCommand(Context context, String arg) throws IOException {
        RECOVERY_DIR.mkdirs();  // In case we need it
        COMMAND_FILE.delete();  // In case it's not writable
        LOG_FILE.delete();

        FileWriter command = new FileWriter(COMMAND_FILE);
        try {
            command.write(arg);
            command.write("\n");
        } finally {
            command.close();
        }

        // Having written the command file, go ahead and reboot
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        pm.reboot("recovery");

        throw new IOException("Reboot failed (no permissions?)");
    }
}