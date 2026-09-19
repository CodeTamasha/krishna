# 🕉️ KRISHNA — AI Voice Assistant (Full Project)

> JARVIS-style personal AI assistant — Hinglish me, OPPO/ColorOS ke liye tuned.

---

## 🆕 v1.1.0 — MAJOR FIXES (2026-09-19)

| Pichla Problem | Ab Ka Fix |
|---|---|
| **Code compile hi nahi ho raha tha** (`ParsedResponse` class do baar define thi — nested + top-level) | Ek hi `ParsedResponse` class — build clean |
| **Koi app open nahi ho rahi thi** | ⭐ Asli wajah: Android 11+ **package visibility** — `QUERY_ALL_PACKAGES` + `<queries>` add hua. Plus app-open ab **Accessibility service ke bina bhi** chalta hai (`AppLauncher`) |
| **"TTS bahut late aata tha"** | ⭐ Default engine ab **Android TTS (instant + offline)**. Fish Audio sirf optional (`Constants.TTS_ENGINE = "fish"`). Reply ab memory-save ke **PEHLE** bolta hai — saara Firebase save background me. Har command ke ~8-10 network calls 0-1 ho gaye |
| **Message aane par turant voice nahi** | Notification listener ab audio service khud start karke **turant** bolti hai ("Boss, WhatsApp par X ka message aaya hai: ...") |
| **"Kuch accessibility wala nahi ho raha"** par pata nahi chalta tha | ⭐ OPPO accessibility band kare to **notification + voice warning** (watchdog har 60s check karta hai) |
| **Contact name se call fail** | ⭐ Ab Pehle **Contacts database se direct number** milta hai (`ContactResolver`) — dialer UI-clicking sirf fallback. Calls bhi accessibility ke bina chalti hain |
| **Messages Firebase me overwrite ho jate the** | Unique message keys (`msg_` + millis + sequence) |
| **App crash — mic permission na ho to `startForeground` SecurityException** | Permission guard + try-catch (Android 12+) |
| **Permission naam galat tha** (`MODIFY_AUDIO_STATE`) | `MODIFY_AUDIO_SETTINGS` |
| **System prompt adhoora load ho sakta tha** (single `read()`) | Poora stream read loop |
| **Mute app restart par reset** | SharedPreferences persist |
| **Stop/unmute ke fixed delays (3s/4.5s) — echo risk** | TTS complete hone par hi agla state (deterministic) |
| **Stats double-count** | Ek hi jagah increment |
| **Broken GitHub workflow** (`unzip.yml` — zip repo me nahi tha) | Delete |
| **Gradle Tencent mirror** | Official `services.gradle.org` |
| **No `.gitignore`** | Added (build/, .gradle, *.apk, .idea waghera) |
| **Wake word me dead Devanagari entries** | Hatai — sirf Latin variants (STT en-IN Latin me type karta hai) |
| **Contact call "People" tab nahi milta (Google Dialer)** | "people" + "contacts" dono try |
| **Chat UI** | User right / Krishna left bubbles + auto-scroll |

**TTS engine badalna** — `app/src/main/java/com/krishna/assistant/Constants.java`:
```java
public static final String TTS_ENGINE = "android";  // ⚡ fast + offline (default)
// public static final String TTS_ENGINE = "fish";  // Fish Audio premium voice (thoda late)
```

**WhatsApp flow (ab polling-based — phone ki speed ke hisaab se):**
```
1. WhatsApp kholo (already khula ho to SKIP)
2. Search icon click (3 attempts)
3. "Rupesh" type (Latin script)
4. ⭐ PEHLA RESULT CLICK — poll: result aane tak wait (max 4s)
5. ⭐ Chat ka BOTTOM INPUT BOX me message type — poll: box aane tak wait
6. 1.5s wait (WhatsApp processing)
7. SEND (3 strategies × 3 retries)
```

---

## ✅ 1. Pichle Version Ke Kaunse Problems THE GAYE

| Purani Problem | Fix Kaise Ki |
|---|---|
| **App khulta-band hota tha, mic loop chal raha tha** | Foreground services (3) + persistent notifications + WakeLock + har jagah try-catch + recognizer auto-restart. Service ab mat marte. |
| **Mic baar-baar khud on ho raha tha (echo loop)** | State machine: Krishna **bolti waqt mic POORA BAND** hai. TTS khatam → 800ms grace → phir mic. Apni awaaz ab nahi sunta. |
| **Icon par baar-baar tap karna padta tha** | **GEMINI-JAIS LIVE MODE**: "Hey Krishna" bolo → ek beep → seedha bolo. Krishna live rehta hai, har reply ke baad AUTO se phir sunne lagta hai. Jab tak **"chup ho ja"** / "band kar" / "so ja" / "bye" na bolo, loop chalta hai. |
| **WhatsApp Hindi Devanagari me search kar raha tha** | (1) System prompt me AI ko MANA: sirf English/Latin letters. (2) Code me Devanagari guard — Devanagari aaye to action skip + AI Hinglish retry karega. |
| **Search box me hi message type ho jata tha** | Naya flow: search → **pehle result par CLICK** (chat khulta hai) → phir chat ka **bottom input box** me message type → send. |
| **Send button nahi milta tha** | 4 strategies × 3 retries (1s gap): content-desc "Send" → view-id "send" → bottom-right circular button → coordinates click. |
| **"YouTube app not found" + video play nahi hota** | Correct package `com.google.android.youtube` + fuzzy fallback. Flow: kholo → search icon → query type → submit → **pehla video result click** (khud chalega). "Video ruk/chala" = center tap (pause/resume). |
| **Notifications bich me baat kat dete the** | Single audio queue: Krishna bol raha hai → message **queue me** → kaam khatam → automatically message padhta hai. |
| **Calls nahi ho paa rahe the** | Number → `tel:` direct call (DIAL fallback). Contact name → dialer → People tab → search → pehla result → green CALL button click. |
| **Manifest me permissions chhute hue the** | Ab saare 20+ permissions hain (mic, overlay, calls, contacts, bluetooth, wifi, camera, battery, boot...). Wizard me har ek request hota hai. |
| **Gradle version mismatch** | Ek hi matching set: **AGP 7.4.2 + Gradle 7.5 + compileSdk 33** (JDK 11 ya 17 dono par chalta hai). |
| **Sirf 8-10 apps the** | 40+ apps hardcoded + **fuzzy matcher** (installed apps ke label se) — "camera kholo" bhi uske phone ke camera app ke naam se chala jayega. |

---

## 🔑 2. SETUP — 5 MINUTE

### Step 1: API Keys daalo (ZAROORI)
File kholo: `app/src/main/java/com/krishna/assistant/Constants.java`

Upar ki 4 lines me keys daalo:
```java
public static final String MERCURY_API_KEY = "TMERI_MERCURY_KEY";
public static final String FISH_API_KEY = "TMERI_FISH_KEY";
public static final String FIREBASE_DB_URL = "https://xxx-default-rtdb.firebaseio.com";
public static final String FIREBASE_SECRET = "";   // public rules hain to KHALI chhodo
```

- **Firebase**: Tere rules public hain to `FIREBASE_SECRET` khali hi rehne do — kaam kar lega. Data `krishna/users/{device_id}/` ke neeche save hota hai.
- **Service account JSON ki zaroorat NAHI** — REST API + public rules kaafi hai (Admin SDK install karna padta nahi).

### Step 2: Build
Project ko Android IDE app me open karo aur build karo.

**Version matching (already set hai — mat badalna):**
- Root `build.gradle` → AGP **7.4.2**
- `gradle/wrapper/gradle-wrapper.properties` → Gradle **7.5**
- `app/build.gradle` → compileSdk **33**, minSdk **26**, targetSdk **33**
- Dependencies: appcompat 1.6.1, material 1.9.0, okhttp 4.12.0, gson 2.10.1, core-ktx 1.10.1, constraintlayout 2.1.4

> ⚠️ Agar tera IDE **JDK 11** use karta hai → yeh set perfect hai.
> Agar **JDK 17 + targetSdk 34** chahiye to neeche Section 5 ka snippet use karo.

### Step 3: Install + Setup Wizard
App kholo → 10-step wizard khud chalega (permissions, overlay, accessibility, notification listener, battery, OPPO auto-start, lock in recents). Har step par "Aage" dabate jao.

### Step 4: OPPO Special (wizard khud khol dega, manually bhi kar sakte ho)
1. **Battery**: Settings → Battery → Background management → Krishna → **Don't optimize**
2. **Auto-Start**: Settings → Apps → Auto-Start → **Krishna ON**
3. **Lock in Recents**: Recents dabao → Krishna long-press → **🔒 Lock**

---

## 🎤 3. USE KARNA (Live Mode)

1. App kholo → **🎙️ Krishna Start** (ya floating button tap)
2. **"Hey Krishna"** bolo → chhota beep → LIVE MODE ON
3. Seedha bolo: *"Rahul ko message kar ki kal milte hain"*
4. Krishna: AI sochta hai → kaam karta hai → bolti hai → **AUTO se phir sunne lagta hai**
5. Baat khatam karne ke liye: **"Chup ho ja"** / "So ja" / "Bye"

**Floating button (har app par):**
- **Tap** → start/stop live
- **Drag** → kahin bhi le jao
- **Long press** → main screen
- Colors: ⚪ gray idle • 🟢 green pulse (sun raha) • 🔵 blue spin (soch raha) • 🟡 gold wave (bol raha)

**Sample commands:**
- "WhatsApp khol" / "YouTube khol" / "Camera kholo"
- "Rupesh ko message kar ki kal milte hain"
- "YouTube par lofi music search kar"
- "98765 43210 ko call karo" / "Ammu ko call kar"
- "Kya time hai?" / "Aaj ki date kya hai?"
- "Volume badha" / "WiFi band kar" / "Torch on"
- "Screenshot le" / "Screen lock kar" / "Home par ja"
- "Kaunsa message aaya?" / "Iska reply kar ki theek hai"
- "Mute kar" / "Unmute" / "Chup ho ja"

---

## ☁️ 4. FIREBASE (Memory System)

Rules **public** hain — isliye secret ke bina kaam karta hai.

**Data structure** (auto banega):
```
krishna/users/{device_id}/
├── profile/            → name, preferences, first_seen, last_active
├── memory/short_term/summary       → compressed summary (har 50 msgs)
├── memory/long_term/facts/         → "User ka naam Rahul hai" waghera
├── memory/last_notification/       → "kaunsa message aaya" ke liye
├── conversations/current_session/messages/  → last 50 (auto-prune)
├── conversations/history/          → archived sessions
├── notifications/
└── stats/
```

**Memory kaise kaam karta hai:**
- AI ko sirf **last 7 messages + summary + facts + profile** jaata hai (2000 tokens se kam)
- **Har 50 messages** ke baad Mercury se 3-line summary → purane delete (last 10 bridge)
- "Mera naam Rahul hai" → fact extract → profile update → hamesha yaad

---

## 🔧 5. AGAR JDK 17 + TARGETSDK 34 CHAHIYE

Root `build.gradle` me:
```groovy
classpath 'com.android.tools.build:gradle:8.1.4'
```
`gradle/wrapper/gradle-wrapper.properties` me:
```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.2-bin.zip
```
`app/build.gradle` me:
```groovy
compileSdkVersion 34
targetSdkVersion 34
```
Dependencies update karo:
```groovy
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.core:core-ktx:1.12.0'
```
Aur manifest me FloatingButtonService ke liye (target 34 foreground type):
```xml
<service
    android:name=".FloatingButtonService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:stopWithTask="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Voice assistant floating control button" />
</service>
```

---

## 📲 6. WHATSAPP FLOW (Kaise Kaam Karta Hai)

```
1. WhatsApp kholo              (2.5s wait)
2. Search icon click           (3 attempts)
3. "Rupesh" type               (Latin script — Devanagari Nahi)
4. ⭐ PEHLA RESULT CLICK        ← chat me ghusna (purana bug yahi tha)
5. BOTTOM input box me message
6. 1.8s wait                   (WhatsApp processing)
7. SEND (4 strategies × 3 retries)
```

> WhatsApp update se UI badle to bas strategy 3/4 (position-based) chalti rahegi.

## ▶️ 7. YOUTUBE FLOW

```
1. com.google.android.youtube kholo  (3.5s)
2. Search icon → query type → submit (search button ya pehli suggestion)
3. ⭐ PEHLA VIDEO RESULT CLICK → video khud chalega
4. "Video ruk" / "Video chala" → screen center tap
```

---

## 🩺 8. TROUBLESHOOTING

| Problem | Solution |
|---|---|
| Krishna awaaz nahi deta | 1) FISH_API_KEY daala? 2) Media volume on? 3) Fail ho to Android TTS fallback khud chalta hai |
| AI reply nahi aata | MERCURY_API_KEY check karo; "dikkat ho rahi hai" wali line = 3 retries fail hue |
| "Hey Krishna" nahi sunta | Mic permission de; network on (STT cloud hai); `Constants.SPEECH_LANGUAGE` "hi-IN" try karo agar Hindi better aaye |
| Service background me band | Battery + Auto-Start + Lock in recents teeno kiye? (Section 2 Step 4) |
| WhatsApp send nahi hua | Pehle manually ek baar WhatsApp search karo (cache banegi); phir command dobara |
| YouTube "not found" | YouTube app install hai? Fuzzy matcher uska label se bhi dhoondta hai |
| Call nahi hua | CALL_PHONE permission de; contact name English me honi chahiye |
| Brightness nahi badhti | WRITE_SETTINGS permission (wizard step 8) |

---

## 📁 9. FILE STRUCTURE

```
Krishna/
├── build.gradle                  ← AGP 7.4.2
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   ← Gradle 7.5
└── app/
    ├── build.gradle              ← SDK 33, dependencies
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   ← saare permissions + 5 services + boot receiver
        ├── assets/krishna_system_prompt.txt   ← AI ki personality + actions
        ├── java/com/krishna/assistant/
        │   ├── MainActivity.java            ← UI + 10-step setup wizard
        │   ├── VoiceListenerService.java    ← ⭐ LIVE MODE (wake word → loop)
        │   ├── KrishnaAccessibilityService.java  ← ⭐ phone control engine
        │   ├── AudioPlaybackService.java    ← Fish TTS + queue + fallback
        │   ├── FloatingButtonService.java   ← draggable animated overlay
        │   ├── NotificationListenerService.java ← messages padhna
        │   ├── ApiHelper.java               ← Mercury + Fish Audio calls
        │   ├── FirebaseHelper.java          ← REST API (public rules OK)
        │   ├── MemoryManager.java           ← smart memory (compress + facts)
        │   ├── CommandParser.java           ← AI response se action JSON
        │   ├── ActionExecutor.java          ← saare actions execute
        │   ├── DeviceUtils.java             ← device ID, OPPO helpers
        │   ├── Constants.java               ← 🔑 API KEYS YAHAN
        │   ├── BootReceiver.java            ← restart par auto-start
        │   ├── ChatMessage.java / UserProfile.java / ParsedResponse.java
        └── res/  (layouts, dark+gold theme, drawables, xml configs)
```

---

**Happy commanding, Boss! 🕉️**
