package com.example.fm;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;

/** Signs APKs (v1 + v2) with the app's bundled key (assets/fm_key.pk8 + fm_cert.der). */
public final class ApkSign {
    private ApkSign() {}

    public static void sign(InputStream pk8, InputStream cert, File in, File out) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(pk8)));
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(cert);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("fm", key, Collections.singletonList(c)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
