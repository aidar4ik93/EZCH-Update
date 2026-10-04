package com.example.ezchupdate.data

import java.util.Locale

/** Exact payloads and legacy catalog versions excluded pending a security review. */
object QuarantinedApks {
    private val hashes = setOf(
        "ae3aa779708fc2d32c298871af6cc139ca4864c18bf8275a22d1726da323a8bc",
        "8525943680e10080489107c6a52df13dc459c832c3ee8f550ad0ada669bac74c",
        "3d84816985f927cc9aecae2015206423d431e402a4a8cc2603e210a21d5620e1"
    )
    private val legacyVersions = setOf(
        "com.spocky.projengmenu" to 92L,
        "jp.snowlife01.android.appkiller2" to 43L,
        "com.play.pandafref" to 1001L
    )

    fun isQuarantined(app: RemoteApp): Boolean =
        app.sha256?.lowercase(Locale.ROOT)?.let { it in hashes } == true ||
            (app.packageName to app.versionCode) in legacyVersions
}
