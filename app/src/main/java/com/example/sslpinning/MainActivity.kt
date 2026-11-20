package com.example.sslpinning

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Two ways to pin:
 *  1. OkHttp CertificatePinner (pins chosen at runtime from the spinner)
 *  2. Network Security Config (res/xml/network_security_config.xml), enforced by the platform
 *     for HttpsURLConnection AND for OkHttp, since both use the platform TrustManager.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var pinSetSpinner: Spinner
    private lateinit var log: TextView

    private val pinSets: LinkedHashMap<String, List<String>> = linkedMapOf(
        "A + B (current + backup)" to listOf(BuildConfig.PIN_A, BuildConfig.PIN_B),
        "A only (no backup pin)" to listOf(BuildConfig.PIN_A),
        "B only (post-rotation build)" to listOf(BuildConfig.PIN_B),
        "C only (wrong pin)" to listOf(BuildConfig.PIN_C),
        "None (NSC pins only)" to emptyList(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        urlInput = EditText(this).apply {
            setText("https://${BuildConfig.TEST_HOST}:${BuildConfig.TEST_PORT}/hello")
            setSingleLine()
        }
        pinSetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                pinSets.keys.toList(),
            )
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label("Server URL"))
            addView(urlInput)
            addView(label("OkHttp CertificatePinner pin set"))
            addView(pinSetSpinner)
            addView(button("Test OkHttp + CertificatePinner") { runTest(::testOkHttp) })
            addView(button("Test HttpsURLConnection (NSC pins only)") { runTest(::testUrlConnection) })
            addView(button("Clear log") { log.text = "" })
            addView(label("Configured pins"))
            addView(TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                text = "A ${BuildConfig.PIN_A}\nB ${BuildConfig.PIN_B}\nC ${BuildConfig.PIN_C}"
            })
            addView(label("Log"))
            addView(log, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun runTest(test: (String) -> String) {
        val url = urlInput.text.toString().trim()
        io.execute {
            val result = try {
                "PASS  " + test(url)
            } catch (e: Exception) {
                "FAIL  " + describe(e)
            }
            runOnUiThread { append(result) }
        }
    }

    private fun testOkHttp(url: String): String {
        val setName = pinSetSpinner.selectedItem as String
        val pins = pinSets.getValue(setName)
        val host = url.toHttpUrl().host

        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        if (pins.isNotEmpty()) {
            builder.certificatePinner(
                CertificatePinner.Builder().apply { pins.forEach { add(host, it) } }.build()
            )
        }
        // A new client per test means a fresh connection pool, so every tap does a real handshake.
        builder.build().newCall(Request.Builder().url(url).build()).execute().use { response ->
            val leaf = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
            val leafPin = leaf?.let { CertificatePinner.pin(it) } ?: "?"
            return "[OkHttp, pins: $setName]\n      HTTP ${response.code} ${response.body?.string()}\n" +
                "      server leaf = ${nameOf(leafPin)} $leafPin"
        }
    }

    private fun testUrlConnection(url: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Connection", "close")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return "[HttpsURLConnection, NSC]\n      HTTP ${conn.responseCode} $body"
        } finally {
            conn.disconnect()
        }
    }

    private fun nameOf(pin: String) = when (pin) {
        BuildConfig.PIN_A -> "(key A)"
        BuildConfig.PIN_B -> "(key B)"
        BuildConfig.PIN_C -> "(key C)"
        else -> "(unknown key)"
    }

    /** Shows which layer rejected the connection:
     *  - "Certificate pinning failure!" -> OkHttp CertificatePinner
     *  - "Pin verification failed"      -> Network Security Config pin-set
     *  - "Trust anchor ... not found"   -> chain not trusted at all */
    private fun describe(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) sb.append("\n      caused by ")
            sb.append(t.javaClass.simpleName).append(": ").append(t.message?.trim())
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    private fun append(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.text = "$ts $line\n\n${log.text}"
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 24, 0, 4)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
