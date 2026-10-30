package com.example.homeezch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class DesktopProfile(val id: String, val name: String, val credential: String = "")

/** Local configuration lock, not a replacement for Android parental controls. */
internal object PinCredential {
    fun create(pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "120000:${hex(salt)}:${hex(derive(pin, salt, 120000))}"
    }
    fun verify(pin: String, credential: String): Boolean = runCatching {
        if (!pin.matches(Regex("[0-9]{4,8}"))) return false
        val parts = credential.split(':')
        require(parts.size == 3 && parts[0] == "120000" && parts[1].length == 32 && parts[2].length == 64)
        MessageDigest.isEqual(derive(pin, unhex(parts[1]), 120000), unhex(parts[2]))
    }.getOrDefault(false)
    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal object Profiles {
    private fun prefs(context: Context) = context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
    fun list(context: Context): List<DesktopProfile> = runCatching {
        val array = JSONArray(prefs(context).getString("profiles_v1", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            DesktopProfile(it.getString("id"), it.getString("name"), it.optString("credential"))
        } }.let { profiles ->
            require(profiles.size <= 8 && profiles.map { it.id }.distinct().size == profiles.size)
            require(profiles.all { it.id.matches(Regex("[A-Za-z0-9-]{1,64}")) && it.name.isNotBlank() })
            if (profiles.none { it.id == "owner" }) listOf(DesktopProfile("owner", "Владелец")) + profiles else profiles
        }
    }.getOrDefault(listOf(DesktopProfile("owner", "Владелец")))
    fun active(context: Context): String = prefs(context).getString("active_profile", "owner")
        ?.takeIf { id -> list(context).any { it.id == id } } ?: "owner"
    fun workspaceKey(context: Context) = if (active(context) == "owner") "workspace_v1" else "workspace_profile_${active(context)}"
    fun guard(context: Context): DesktopProfile? = list(context).firstOrNull { it.id == "owner" && it.credential.isNotEmpty() }
        ?: list(context).firstOrNull { it.id == active(context) && it.credential.isNotEmpty() }
    fun save(context: Context, profiles: List<DesktopProfile>): Boolean {
        require(profiles.size in 1..8 && profiles.any { it.id == "owner" })
        val data = JSONArray()
        profiles.forEach { data.put(JSONObject().put("id", it.id).put("name", it.name).put("credential", it.credential)) }
        return prefs(context).edit().putString("profiles_v1", data.toString()).commit()
    }
    fun switch(context: Context, id: String): Boolean = list(context).any { it.id == id } &&
        prefs(context).edit().putString("active_profile", id).commit()
    fun check(context: Context, profile: DesktopProfile, pin: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        val prefix = "pin_${profile.id}_"
        if (p.getLong(prefix + "until", 0) > now) return false
        val valid = profile.credential.isEmpty() || PinCredential.verify(pin, profile.credential)
        val attempts = if (valid) 0 else p.getInt(prefix + "attempts", 0) + 1
        p.edit().putInt(prefix + "attempts", if (attempts >= 5) 0 else attempts)
            .putLong(prefix + "until", if (attempts >= 5) now + 30000 else 0).commit()
        return valid
    }
}
