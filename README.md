# Glass Launcher 2.0

Open-source launcher za Android TV (Mi Box) sa zamućenim staklenim pločicama, samuraj pozadinom i bez reklama.
Interfejs je na engleskom, bosanskom i njemačkom.

## Šta ima
- **Početni ekran samo sa +.** Aplikacije koje želiš dodaješ sam: iz instaliranih, preko Google Playa, s telefona ili iz APK fajla.
- **Stakleni izgled.** Zamućena pozadina se vidi kroz pločice, statusnu traku i menije. Font je Inter, a ikone su tanke, u iOS stilu.
- **Vlastite ikone** za svaku aplikaciju (slika s boxa, iz file browsera ili s interneta), uz pomjeranje, preimenovanje i uklanjanje.
- **Statusna traka:** vrijeme, Wi-Fi s imenom mreže ili LAN, VPN ključ (zeleno = radi, crveno = ne radi), obavještenja, CPU % i RAM %. Svaki dio se može uključiti ili isključiti.
- **Dok gledaš film:** u gornjem desnom ćošku ostaje samo VPN ključ, a po želji i sat.
- **Sat:** kao Android, 24h ili 12h, opcija za sekunde i automatska vremenska zona.
- **Profili** s vlastitim aplikacijama, pozadinom i postavkama, plus prečica do Google računa (prijava, odjava, promjena).
- **Backup i vraćanje** svih profila, ikona, pozadina i postavki u jedan fajl.
- **File browser bez reklama,** koji se vidi i kao posebna aplikacija "Fajlovi".
- **Zadani launcher:** dugme Home na daljinskom otvara Glass Launcher.
- **Napajanje:** spavanje, restart i gašenje.
- **Screensaver:** samuraj sa satom, i opcija šta box radi kad miruje.
- **Private DNS** (AdGuard, Cloudflare, Google, Quad9 ili vlastiti server).
- **Blokiranje auto-starta** aplikacija nakon paljenja boxa.
- **Update preko interneta.**

## Daljinski
| Šta | Kako |
|---|---|
| Otvori aplikaciju | OK |
| Meni aplikacije (ikona, pomjeri, preimenuj, ukloni) | **Drži OK** ili dugme Menu |
| Postavke | Zupčanik gore desno, ili drži OK na + |
| Profil | Krug sa slovom gore desno |
| Napajanje | Dugme za gašenje gore desno |

## 1. Napravi APK (GitHub)
1. Uploaduj sve fajlove iz ovog foldera u svoj GitHub repozitorij, zajedno sa `.github/workflows/build.yml`.
2. Otvori tab **Actions**, pa "Build APK". Nakon oko 5 minuta pod Artifacts preuzmi **GlassLauncher-apk**.

## 2. Jednokratna ADB komanda (preporučeno)
Na boxu uključi Opcije za programere i mrežno otklanjanje grešaka, pa na računaru pokreni:
```
adb connect <IP-boxa>
adb shell pm grant com.glass.launcher android.permission.WRITE_SECURE_SETTINGS
```
Ova komanda omogućava da launcher sam uključi:
- dugme Home
- ikonicu u ćošku
- Private DNS
- automatsku vremensku zonu

Bez nje sve ostalo i dalje radi, samo te za ove opcije šalje u postavke Androida.

## 3. Update preko interneta
- U `app/build.gradle.kts` povećaj `versionCode` (npr. sa 2 na 3) i promijeni `versionName`.
- Napravi tag, npr. `v2.1`. GitHub tada sam napravi Release sa `GlassLauncher.apk` i `update.json`.
- Na boxu otvori Postavke → Ažuriranje → Adresa za update i upiši:
  `https://github.com/<TVOJ-USER>/<REPO>/releases/latest/download/update.json`

Svi buildovi su potpisani istim ključem (`app/glass.keystore`), pa svaka nova verzija može ažurirati prethodnu. Ako zamijeniš ključ, novi moraš zadržati zauvijek.

## Ograničenja Androida (nije greška)
- **Prijava u Google račun i lozinke:** njih čuva Android, ne launcher. Launcher otvara stranicu s računima u postavkama.
- **CPU %:** novije verzije Androida ne daju aplikacijama stvarno opterećenje procesora. Tada se prikazuje procjena na osnovu brzine procesora.
- **Ime Wi-Fi mreže (SSID):** Android ga pokazuje samo uz dozvolu za lokaciju, koju launcher traži jednom.

Licenca: MIT. Ikone su iz seta Lucide (ISC licenca), a font je Inter (SIL OFL).
