package cn.jxnu.nvzhuanban.data.network

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 复刻 JSEncrypt 的 RSA 加密：
 *  - 算法：RSA / ECB / PKCS1Padding（JSEncrypt 默认）
 *  - 输出：Base64（无换行）
 *  - 最终值需在前面拼上字面量 `__RSA__`，CAS 服务端据此识别是加密密码而非明文
 *
 * 公钥支持 3 种格式自动识别：
 *  1. PEM（`-----BEGIN PUBLIC KEY-----...-----END PUBLIC KEY-----`），X.509 SubjectPublicKeyInfo
 *  2. JWK（`{"kty":"RSA","n":"...","e":"AQAB"}`），用 n/e 字段
 *  3. 单行 Base64（裸 SPKI），整段直接 base64 解码再走 X.509
 *
 * 之所以做容错：江西师大 CAS 的 /cas/jwt/publicKey 接口在浏览器 HAR 里只看到 size，
 * 没看到响应体，所以无法预先确定它返回的是哪一种格式。
 */
object RsaPasswordEncryptor {

    const val PREFIX = "__RSA__"

    /** 一步到位：明文密码 + 公钥材料 → CAS 接受的最终字符串。 */
    fun encryptPassword(password: String, publicKeyMaterial: String): String {
        val key = parsePublicKey(publicKeyMaterial)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    internal fun parsePublicKey(material: String): PublicKey {
        val trimmed = material.trim()
        return when {
            trimmed.startsWith("{") -> parseJwk(trimmed)
            trimmed.contains("BEGIN") -> parsePem(trimmed)
            else -> parseBase64Spki(trimmed)
        }
    }

    private fun parsePem(pem: String): PublicKey {
        val cleaned = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s+"), "")
        return parseBase64Spki(cleaned)
    }

    private fun parseBase64Spki(b64: String): PublicKey {
        val der = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun parseJwk(json: String): PublicKey {
        val obj = JSONObject(json)
        val nB64 = obj.getString("n")
        val eB64 = obj.getString("e")
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val modulus = BigInteger(1, Base64.decode(nB64, flags))
        val exponent = BigInteger(1, Base64.decode(eB64, flags))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }
}
