package com.sakhand.downloadmanager.engine

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * DNS هوشمند — مقاوم در برابر خرابی و مسمومیت DNS شرکت‌های اینترنت ایران
 *
 * بعضی دامنه‌های خارجی (مخصوصاً دامنه‌های شخصی کوچک) روی بعضی شبکه‌های ایران
 * یا اصلاً resolve نمی‌شوند یا آدرس مسدودکننده برمی‌گردانند. این کلاس اول از
 * DNS خود دستگاه استفاده می‌کند؛ اگر پاسخ خراب بود، از DNS-over-HTTPS با
 * این ترتیب کمک می‌گیرد:
 *   ۱) شکن (داخل ایران — همیشه در دسترس) برای کاربران ایرانی
 *   ۲) Quad9
 *   ۳) Cloudflare
 * نتیجه‌ها ۱۰ دقیقه کش می‌شوند.
 */
object SmartDns : Dns {

    private const val TTL_MS = 10 * 60 * 1000L

    private class Entry(val addrs: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val dohTransport = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // شکن — سرورهای داخل ایران، همیشه قابل دسترس برای کاربر ایرانی
    private val shecan: Dns by lazy {
        doh("https://free.shecan.ir/dns-query", "178.22.122.100", "185.51.200.2")
    }
    private val quad9: Dns by lazy {
        doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112")
    }
    private val cloudflare: Dns by lazy {
        doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1")
    }

    private fun doh(url: String, vararg bootstrap: String): Dns =
        DnsOverHttps.Builder()
            .client(dohTransport)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
            .includeIPv6(false)
            .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.at < TTL_MS) return it.addrs }

        val addrs = resolve(hostname)
        if (addrs.isNotEmpty()) {
            cache[hostname] = Entry(addrs, now)
            return addrs
        }
        throw UnknownHostException("هیچ آدرسی برای $hostname پیدا نشد")
    }

    private fun resolve(hostname: String): List<InetAddress> {
        // ۱) DNS خود دستگاه
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrNull().orEmpty().filterIsInstance<Inet4Address>()
        if (system.isNotEmpty() && system.none { isSuspicious(it) }) return system

        // ۲) مسیرهای DoH — شکن، Quad9، کلادفلر
        for (doh in listOf(shecan, quad9, cloudflare)) {
            val via = runCatching { doh.lookup(hostname) }
                .getOrNull().orEmpty().filterIsInstance<Inet4Address>()
            if (via.isNotEmpty()) return via
        }

        // ۳) آخرین راه: همان پاسخ سیستم، حتی اگر مشکوک بود
        return system
    }

    /** IPهای مسدودکننده/مسموم — رنج خصوصی و لوکال که فیلترینگ برمی‌گرداند */
    private fun isSuspicious(addr: InetAddress): Boolean {
        val h = addr.hostAddress ?: return true
        return addr.isSiteLocalAddress || addr.isLoopbackAddress ||
            addr.isAnyLocalAddress || h.startsWith("10.") || h.startsWith("127.")
    }
}

/** ابزار ساخت کلاینت تساهل‌گر — برای عبور از خطای گواهی (ساعت اشتباه دستگاه یا دستکاری میانه TLS) */
object LenientTls {

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    private val sslFactory: javax.net.ssl.SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), java.security.SecureRandom()) }.socketFactory
    }

    /** نسخه‌ای از کلاینت که خطای گواهی TLS را نادیده می‌گیرد */
    fun apply(builder: OkHttpClient): OkHttpClient = builder.newBuilder()
        .sslSocketFactory(sslFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    /** آیا این خطا از جنس TLS/DNS است که تساهل‌گر می‌تواند حل کند؟ */
    fun isTlsOrDnsError(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            if (cur is javax.net.ssl.SSLException) return true
            if (cur is javax.net.ssl.SSLPeerUnverifiedException) return true
            if (cur is java.net.UnknownHostException) return true
            cur = cur.cause
        }
        return false
    }
}
