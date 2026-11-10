package com.asanvpn.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log

/**
 * Helper to bypass Iranian applications from the VPN tunnel.
 *
 * Excludes banking, crypto exchange, ride-hailing, marketplace,
 * and domestic messenger applications so that their network traffic
 * exits directly through the physical network interface without
 * entering the VPN tunnel.
 */
object IranAppBypassHelper {

    private const val TAG = "IranAppBypass"

    val IRANIAN_PACKAGES: List<String> = listOf(
        // Cryptocurrency Exchanges
        "app.nobitex", "market.nobitex", "ir.nobitex",               // Nobitex (نوبیتکس)
        "ir.wallex.app",                                            // Wallex (والکس)
        "com.abantether.app",                                       // AbanTether (آبان‌تتر)
        "com.ramzinex.app",                                         // Ramzinex (رمزینکس)
        "com.bitpin.app",                                           // Bitpin (بیت‌پین)
        "com.sarmayex.app",                                         // Sarmayex (سرمایکس)
        "ir.exir.app",                                              // Exir (اکسیر)
        "io.ompfinex.android",                                      // Ompfinex (اوام‌پی فینکس)
        "ir.tabdeal.android",                                       // Tabdeal (تبدیل)

        // Mobile Payment & Services
        "com.sibche.aspardproject.app", "ir.nasimpay.app", "com.asanpardakht.app", // Up / Asan Pardakht (آپ - آسان پرداخت)
        "ir.mizan.hamrahcard",                                      // Hamrah Card (همراه کارت)
        "ir.sep.android",                                           // 724 / Saman Kish (۷۲۴)
        "ir.pec.toptop",                                            // Top / Parsian (تاپ)
        "ir.behpardakht.sekeh",                                     // Sekeh / Behpardakht Mellat (سکه)
        "com.digipay.app",                                          // Digipay (دیجی‌پی)
        "com.sadadpsp.iva",                                         // Iva / Sadad (ایوا)

        // App Stores
        "com.farsitel.bazaar",                                      // Cafe Bazaar (کافه بازار)
        "ir.mservices.market",                                      // Myket (مایکت)
        "com.charkhoneh.android",                                   // Charkhoneh (چارخونه)

        // Ride-Hailing, Delivery & Navigation
        "cab.snapp.passenger", "cab.snapp.driver", "cab.snapp.box", // Snapp (اسنپ)
        "com.zoodfood.android",                                     // SnappFood (اسنپ‌فود)
        "taxi.tap30.passenger", "taxi.tap30.driver",                // Tapsi (تپسی)
        "com.maximtaxi.client",                                     // Maxim (ماکسیم)
        "org.neshan.maps",                                          // Neshan (نشان)
        "ir.balad",                                                 // Balad (بلد)

        // E-Commerce & Marketplaces
        "ir.divar",                                                 // Divar (دیوار)
        "com.digikala.app",                                         // Digikala (دیجی‌کالا)
        "com.torob", "ir.takhfifan.torob",                          // Torob (ترب)
        "ir.basalam",                                               // Basalam (باسلام)
        "com.sheypoor.mobile",                                      // Sheypoor (شیپور)
        "com.emalls",                                               // Emalls (ایمالز)
        "com.bamilo.android",                                       // Bamilo (بامیلو)

        // Messengers, Social Media & Collaboration
        "ir.resaneh1.iptv",                                         // Rubika (روبیکا)
        "ir.ble.messenger",                                         // Bale (بله)
        "ir.eitaa.messenger",                                       // Eitaa (ایتا)
        "mobi.mm.msenger", "mobi.mm.msenger.plus",                  // Soroush (سروش)
        "ir.gap.messenger",                                         // Gap (گپ)
        "net.iGap",                                                 // iGap (آی‌گپ)
        "ir.medu.shad",                                             // Shad (شاد)
        "com.aparat",                                               // Aparat (آپارات)

        // Telecom Operators
        "ir.mci.ecareapp",                                          // Hamrah-e Aval / MyMCI (همراه اول)
        "ir.mtn.irancell",                                          // Irancell / MyIrancell (ایرانسل)
        "ir.rightel.myrightel",                                     // Rightel (رایتل)
        "com.shatel.my",                                            // MyShatel (شاتل)
        "ir.mobinnet.my",                                           // Mobinnet (مبین‌نت)

        // Banking & Financial Institutions
        "com.sb24.blubank", "com.blubank",                          // BluBank (بلوبانک)
        "ir.bankmellat.mobile", "ir.mellatbank.mobile",             // Bank Mellat (بانک ملت)
        "com.bmi.mobilebank", "ir.bmi.bam",                         // Bank Melli / BAM (بانک ملی و بام)
        "com.tejaratbank.tejaratmobile",                            // Bank Tejarat (بانک تجارت)
        "ir.banksepah.mobile",                                      // Bank Sepah (بانک سپه)
        "com.bpi.app",                                              // Bank Pasargad (بانک پاسارگاد)
        "com.sb24.mobilebank",                                      // Bank Saman (بانک سامان)
        "com.bki.mobilebank",                                       // Bank Keshavarzi (بانک کشاورزی)
        "com.bankrefah.mobilebank",                                 // Bank Refah (بانک رفاه کارگران)
        "com.saderatbank.sepehri",                                  // Bank Saderat (بانک صادرات)
        "com.maskan.mb",                                            // Bank Maskan (بانک مسکن)
        "com.sinabank.mb",                                          // Sina Bank (بانک سینا)
        "com.shahr.mb",                                             // Shahr Bank (بانک شهر)
        "com.ayandeh.mb",                                           // Bank Ayandeh (بانک آینده)
        "com.karafarinbank.mobile",                                 // Bank Karafarin (بانک کارآفرین)
        "com.parsian.mobile",                                       // Bank Parsian (بانک پارسیان)
        "ir.resalat.mb",                                            // Resalat Bank (بانک رسالت)
        "ir.postbank.mb",                                           // Post Bank (پست بانک)

        // Streaming, Media & Books
        "com.greenweb.filimo",                                      // Filimo (فیلیمو)
        "ir.shatel.namava",                                         // Namava (نماوا)
        "com.filmnet",                                              // Filmnet (فیلم‌نت)
        "simayehamrah.android", "ir.telewebion",                    // Telewebion (تلوبیون)
        "ir.mservices.taaghche",                                    // Taaghche (طاقچه)
        "com.fidibo.reader"                                         // Fidibo (فیدیبو)
    )

    /**
     * Applies disallowed applications to the VpnService builder.
     * Only installed packages are added; uninstalled packages throw
     * [PackageManager.NameNotFoundException] which is caught and skipped safely.
     *
     * @param builder The active [VpnService.Builder]
     * @param context Application context
     * @return Number of packages successfully added to the bypass list
     */
    fun applyDisallowedApplications(builder: VpnService.Builder, context: Context): Int {
        var bypassedCount = 0
        for (pkg in IRANIAN_PACKAGES) {
            try {
                builder.addDisallowedApplication(pkg)
                bypassedCount++
            } catch (e: PackageManager.NameNotFoundException) {
                // Application not installed on user's device — safely skip
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disallow package $pkg: ${e.message}")
            }
        }
        Log.i(TAG, "Successfully bypassed $bypassedCount Iranian applications from VPN tunnel")
        return bypassedCount
    }
}
