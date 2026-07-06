package com.hinsight.ai.embedding;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Base64;

/**
 * 임베딩 벡터 유틸. 코사인 유사도 계산과, 캐시/저장을 위한 float[] ↔ Base64 인코딩을 제공한다.
 *
 * <p>Base64 인코딩은 1024차원 float 벡터를 Redis 문자열로 저장할 때
 * JSON 숫자 배열보다 콤팩트하게(4바이트/차원) 담기 위한 것이다.</p>
 */
public final class Vectors {

    private Vectors() {
    }

    /** 코사인 유사도. 두 벡터 길이가 같다고 가정한다. */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    /** float[] → Base64 (리틀·엔디안 아님, JVM 기본 빅엔디안. 인코딩/디코딩이 대칭이면 무방). */
    public static String toBase64(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES);
        for (float f : v) {
            buf.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** Base64 → float[]. {@link #toBase64}로 인코딩한 문자열을 복원한다. */
    public static float[] fromBase64(String s) {
        byte[] bytes = Base64.getDecoder().decode(s);
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }
}
