# Boykta VPN — Native Android App

تطبيق VPN أندرويد احترافي يستخدم Xray-core (VLESS WS) مع واجهة مخصصة وبوت تلغرام للإدارة.

---

## 🚀 البناء التلقائي عبر GitHub Actions (الطريقة الموصى بها)

### الخطوة 1 — إنشاء repository على GitHub

```bash
# من داخل مجلد android-app
git init
git add .
git commit -m "feat: initial Boykta VPN Android app"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/boykta-vpn.git
git push -u origin main
```

### الخطوة 2 — انتظر GitHub Actions

بمجرد رفع الكود، سيبدأ GitHub Actions تلقائياً في بناء الـ APK.  
اذهب إلى: `https://github.com/YOUR_USERNAME/boykta-vpn/actions`

### الخطوة 3 — تحميل الـ APK

بعد اكتمال البناء (~5 دقائق):
- اضغط على الـ workflow run
- في أسفل الصفحة اضغط على **"boykta-vpn-debug"**
- حمّل ملف `app-debug.apk`

---

## 🔨 البناء المحلي (Android Studio)

### المتطلبات
- Android Studio Hedgehog أو أحدث
- JDK 17
- Android SDK (API 35)

### الخطوات

```bash
# 1. تحميل libXray.aar
mkdir -p app/libs
curl -L "https://github.com/2dust/v2rayNG/releases/download/1.9.30/libXray.aar" \
  -o app/libs/libXray.aar

# 2. فتح المشروع في Android Studio
# File → Open → اختر مجلد android-app

# 3. البناء
./gradlew assembleDebug

# APK في: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔐 إعداد SSL Pinning (قبل الإصدار الرسمي)

```bash
# احصل على SHA-256 pin لنطاقك
openssl s_client -connect boykta.boykta.dpdns.org:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | base64
```

ثم ضع القيمة في `ApiClient.kt`:
```kotlin
.add("boykta.boykta.dpdns.org", "sha256/PASTE_HERE==")
```
وفعّل السطر: `.certificatePinner(certificatePinner)`

---

## 🔑 بناء APK موقّع (للنشر)

### إنشاء Keystore
```bash
keytool -genkey -v \
  -keystore boykta-release.jks \
  -alias boykta \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### إضافة Secrets في GitHub
اذهب إلى: `Settings → Secrets and variables → Actions` وأضف:
| Secret | القيمة |
|--------|--------|
| `KEYSTORE_FILE` | محتوى ملف .jks بصيغة base64 |
| `KEY_ALIAS` | `boykta` |
| `KEY_PASSWORD` | كلمة مرورك |
| `STORE_PASSWORD` | كلمة مرور الـ keystore |

---

## 🗺️ هيكل المشروع

```
android-app/
├── .github/workflows/build.yml    # GitHub Actions — بناء تلقائي
├── app/
│   ├── libs/                      # libXray.aar (مُحمَّل في CI)
│   └── src/main/java/com/boykta/vpn/
│       ├── MainActivity.kt        # الشاشة الرئيسية
│       ├── MainViewModel.kt       # منطق UI
│       ├── App.kt                 # Hilt Application
│       ├── api/
│       │   ├── ApiClient.kt       # Retrofit + SSL Pinning + AES decrypt
│       │   └── CryptoHelper.kt    # AES-256-GCM
│       ├── model/
│       │   ├── Server.kt          # بيانات السيرفر + عداد تنازلي
│       │   └── Announcement.kt    # الإعلانات والإشعارات
│       ├── service/
│       │   ├── BoykVpnService.kt  # Android VpnService (اتصال حقيقي)
│       │   └── XrayManager.kt     # Xray-core wrapper
│       ├── ui/
│       │   ├── ServerAdapter.kt   # قائمة السيرفرات
│       │   └── AdDialog.kt        # نافذة الإعلان (15 ثانية)
│       └── util/
│           └── SecurityChecker.kt # كشف برامج الاختراق
```

---

## ⚙️ كيف يعمل الاتصال

```
هاتف المستخدم
     │
     ▼ Android VpnService (TUN interface)
     │
     ▼ tun2socks (داخل libXray.aar)
     │
     ▼ Xray-core (SOCKS5 local proxy on 127.0.0.1:10808)
     │
     ▼ VLESS WS over TLS port 25227
     │
     ▼ Cloudflare → boykta.boykta.dpdns.org
     │
     ▼ الإنترنت ✅
```

---

## 🛡️ ميزات الأمان

| الميزة | التفاصيل |
|--------|---------|
| **تشفير API** | AES-256-GCM، مفتاح SHA-256 مشتق |
| **SSL Pinning** | Certificate pinning بـ OkHttp |
| **إخفاء الكونفيغ** | VLESS URI لا يظهر للمستخدم أبداً |
| **كشف Sniffer** | فحص 15+ تطبيق اختراق معروف |
| **ملفات .config.boykta** | تكوينات مشفرة قابلة للاستيراد |

---

## 📱 متطلبات الجهاز

- Android 8.0 (API 26) أو أحدث
- معمارية: arm64-v8a أو x86_64
- مساحة: ~30 MB
