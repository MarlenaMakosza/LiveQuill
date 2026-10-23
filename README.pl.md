# LiveQuill

[English](README.md) · **Polski**

Odręczny notatnik. Piszesz rysikiem na tablecie z Androidem (z naciskiem), serwer przechowuje wszystko, a okno na komputerze z Linuksem pokazuje notatki na żywo. Okno można przypiąć nad innymi aplikacjami.

```
[tablet Android] --WebSocket--> [serwer :8787] --WebSocket--> [okno na PC]
  SQLite + kolejka               data/ops.jsonl                 przypinane
```

Bez sieci tablet zapisuje wszystko lokalnie i wysyła, gdy serwer znów będzie osiągalny.

---

## 0. Gotowe pliki (bez budowania)

Gotowe wersje są na stronie [Releases](https://github.com/MarlenaMakosza/LiveQuill/releases):

| Plik | Co z nim zrobić |
|---|---|
| `livequill-<wersja>.apk` | zainstaluj na tablecie (punkt 4) |
| `livequill-desktop-<wersja>-linux-x64.tar.gz` | rozpakuj i uruchom w środku `./install-desktop.sh` (Java jest w paczce) |
| `livequill-widget-<wersja>.tar.gz` | widget KDE Plasma: rozpakuj i uruchom `./install-widget.sh --add` |
| `livequill-<wersja>.plasmoid` | ten sam widget do „Zainstaluj widżet z pliku lokalnego” w Plasmie |
| `livequill-server-<wersja>.tar.gz` | serwer bez Dockera: `LIVEQUILL_TOKEN=... ./bin/livequill-server` (Java 17+) |
| `SHA256SUMS` | sumy kontrolne: `sha256sum -c SHA256SUMS` |

Serwer jest też gotowym obrazem Dockera, więc nic nie trzeba budować:

```bash
docker run -d --name livequill --restart unless-stopped -p 8787:8787 \
  -e LIVEQUILL_TOKEN=twoj-token -v "$PWD/data:/data" \
  ghcr.io/marlenamakosza/livequill-server:latest
```

Jeśli korzystasz z gotowych plików, pomiń punkty 1–2 (i budowanie w punkcie 3).

## 1. Wymagania (raz)

Na komputerze potrzebujesz tylko Dockera i rsync. Javy, Gradle'a ani Android SDK nie instalujesz: są w obrazie Dockera.

```bash
sudo pacman -S docker docker-compose rsync
sudo systemctl enable --now docker
sudo usermod -aG docker $USER     # potem wyloguj się i zaloguj ponownie
docker run --rm hello-world       # test: ma działać bez sudo
```

Miejsce na dysku: około 3 GB (obraz budujący, cache Gradle'a i wyniki).

## 2. Budowanie

```bash
cd ~/AI-isolation/hand-notes
./build.sh
```

- Pierwszy build trwa kilka–kilkanaście minut, bo pobiera obraz i zależności (około 1 GB). Kolejne trwają około 20–30 s.
- Na końcu ma się pojawić `BUILD SUCCESSFUL`, a w `dist/` wyniki:

| Plik | Co to |
|---|---|
| `dist/livequill.apk` | aplikacja na tablet |
| `dist/server/` | serwer (używany przez Dockera) |
| `dist/desktop/` | aplikacja na komputer |

Budowanie pojedynczej części: `./build.sh :android:assembleDebug` (to samo dla `:server:installDist` i `:desktop:createDistributable`). W tym trybie wyniki nie są kopiowane do `dist/`.

## 3. Serwer

### Token (raz)

Token to wspólne hasło serwera, tabletu i komputera. Leży w pliku `.env` obok `docker-compose.yml`:

```bash
echo "LIVEQUILL_TOKEN=$(openssl rand -hex 4)" > .env    # tylko jeśli .env jeszcze nie istnieje
cat .env
```

Bez `.env` serwer użyje tokena `change-me`.

### Start

`./build.sh` musi być uruchomiony wcześniej, bo obraz serwera kopiuje gotowy build z `server/build/install/`.

```bash
docker compose up -d --build
curl http://localhost:8787/health        # ma odpowiedzieć: ok
```

Serwer startuje sam po restarcie komputera (`restart: unless-stopped`). Notatki zapisuje w `data/ops.jsonl`.

| Komenda | Co robi |
|---|---|
| `docker compose logs -f` | podgląd na żywo; każde połączenie widać jako `+ nazwa (tablet/viewer)` |
| `docker compose restart` | restart serwera |
| `docker compose down` | zatrzymanie serwera |
| `docker compose up -d --build` | start albo aktualizacja po nowym `./build.sh` |

### Adres IP komputera

Tablet łączy się po IP komputera w sieci domowej:

```bash
ip -4 -o addr show scope global | awk '{print $2, $4}'
```

Weź adres z interfejsu Wi-Fi lub kabla (np. `wlp…` albo `enp…`, wygląda jak `192.168.1.10/24`). Bez `/24` wpisujesz go w tablecie.

Router może dać komputerowi inny adres po restarcie i tablet przestanie się łączyć. Żeby tego uniknąć, ustaw w routerze stały adres dla komputera (rezerwacja DHCP).

Jeśli masz włączony firewall, otwórz port: `sudo ufw allow 8787/tcp` albo `sudo firewall-cmd --add-port=8787/tcp --permanent && sudo firewall-cmd --reload`.

## 4. Tablet

### Instalacja APK

Przeciąganie APK do komunikatorów często kończy się błędem „pusty plik”. Najpewniej pobrać go prosto z komputera:

```bash
cd ~/AI-isolation/hand-notes/dist
python3 -m http.server 8000
```

1. Na tablecie otwórz w przeglądarce `http://<IP-komputera>:8000/livequill.apk`.
2. Otwórz pobrany plik. Android zapyta o zgodę na instalację z nieznanych źródeł dla przeglądarki: zezwól.
3. Na komputerze zatrzymaj serwer plików przez `Ctrl+C`.

Inne sposoby: kabel USB i `adb install -r dist/livequill.apk`, albo KDE Connect.

**Aktualizacja:** zainstaluj nowe APK na stare. Notatki i ustawienia zostają.

Aktualizacja działa tylko wtedy, gdy stare i nowe APK są podpisane tym samym kluczem:

- Jeśli istnieje `~/.config/livequill-signing/signing.env` (patrz „Wydawanie nowej wersji”), `./build.sh` podpisuje `dist/livequill.apk` kluczem release, tym samym co GitHub Releases. Wtedy wersje z własnego buildu i z Releases instalują się jedna na drugą.
- W przeciwnym razie powstaje APK debug, podpisane kluczem z `.cache/android/`.

Przy innym kluczu Android pokaże „konflikt z istniejącym pakietem”. Wtedy odinstaluj starą aplikację i zainstaluj nową. **Przedtem sprawdź, czy status to `● online` bez zmian w kolejce.** Notatki z serwera wrócą same, ale te, które czekają w kolejce, przepadną.

### Pierwsze uruchomienie

Przy pierwszym starcie otwierają się ustawienia (później przycisk ⚙):

- **Adres serwera:** IP komputera, np. `192.168.1.10`. Port 8787 jest domyślny.
- **Token:** wartość z `.env`.

Na pasku status powinien zmienić się na zielone `● online`.

### Obsługa

| Akcja | Jak |
|---|---|
| Pisanie | rysik (palec i dłoń są ignorowane) |
| Przesuwanie tablicy | 1 palec |
| Przesuwanie i zoom | 2 palce |
| Cofnij | tap dwoma palcami albo „↶ Cofnij” |
| Przywróć | tap trzema palcami albo „↷ Ponów” |
| Gumka | przycisk „Gumka”, gumka na końcu rysika albo przycisk boczny rysika podczas pisania |
| Pokaż całą tablicę | „⌖ Wszystko” |
| Tryb ciemny/jasny | „☾ Ciemny” / „☀ Jasny” (domyślnie jak w systemie) |
| Nowa tablica / przełączanie | „+ tablica”, ◀ ▶ |
| Rysowanie palcem | ⚙ → „Rysuj też palcem”: wtedy 1 palec rysuje, 2 przesuwają |

- Interfejs jest po polsku, gdy system jest po polsku. W innym przypadku jest po angielsku.
- Każda tablica jest nieskończona i pamięta, gdzie ją ostatnio oglądałaś.
- Cofanie działa osobno dla każdej tablicy i pamięta 200 kroków w bieżącej sesji. Po restarcie aplikacji „Cofnij” usuwa najnowszą własną kreskę.
- Jeśli tap trzema palcami robi zrzut ekranu, wyłącz w ustawieniach Androida gest zrzutu ekranu trzema palcami.

Status na pasku:

| Status | Znaczenie |
|---|---|
| `● online` | połączony |
| `● offline · N w kolejce` | brak serwera. N zmian czeka i wyśle się samo, także gdy aplikacja jest zamknięta |
| `● zły token` | token w tablecie nie zgadza się z `.env` |
| `● brak serwera` | nie wpisano adresu |

## 5. Komputer

```bash
./install-desktop.sh
```

Skrypt instaluje aplikację do `~/.local/opt/livequill` i dodaje **LiveQuill** do menu KDE. Przy pierwszym starcie wejdź w **Opcje**:

- adres: `localhost`, jeśli serwer działa na tym samym komputerze, inaczej jego IP;
- token: wartość z `.env`.

Ustawienia leżą w `~/.config/livequill/desktop.properties`.

| Akcja | Jak |
|---|---|
| Przypnij nad innymi oknami | „Przypnij” |
| Pokazuj to, co widać na tablecie | „śledź” |
| Przesuwanie | przeciągnij myszą (wyłącza „śledź”; obszar tabletu widać jako przerywaną ramkę) |
| Zoom | kółko myszy |
| Cała tablica | „Wszystko” |
| Tryb ciemny/jasny | „Ciemny” / „Jasny” |

Autostart po zalogowaniu: Ustawienia systemowe KDE → Autostart → Dodaj aplikację → LiveQuill.

## 5a. Widget na pulpit KDE

Widget pokazuje na pulpicie tablicę, na której jesteś na tablecie, i odświeża ją na żywo. Plasma ładuje go przy każdym logowaniu, więc notatka wita Cię zaraz po włączeniu komputera. Widget tylko wyświetla, nic w nim nie piszesz.

Wymagania: Plasma 6 i Qt WebSockets. Na Manjaro/Arch:

```bash
sudo pacman -S --needed qt6-websockets
```

### Instalacja

Z katalogu repozytorium albo z rozpakowanej paczki `livequill-widget-<wersja>.tar.gz`:

```bash
./install-widget.sh --add                      # instalacja i dodanie na pulpit
./install-widget.sh --add 192.168.1.10 TOKEN   # serwer na innym komputerze, token podany wprost
./install-widget.sh                            # tylko instalacja; dodasz go prawym klikiem → Dodaj widżety
```

Z `--add` skrypt ustawia serwer (domyślnie `localhost:8787`) i token z `.env`. Widget pojawia się w lewym górnym rogu pulpitu.

Ręczne dodanie (po `./install-widget.sh` bez `--add`, albo z pliku `.plasmoid`):
1. Prawy klik na pulpicie → **Dodaj lub zarządzaj widżetami**. Plik `.plasmoid` instalujesz w tym panelu przez **Pobierz nowe widżety → Zainstaluj widżet z pliku lokalnego**.
2. Wyszukaj **LiveQuill** i przeciągnij go na pulpit.
3. Prawy klik na widgecie → **Konfiguruj LiveQuill** → wpisz adres serwera i token.

**Przesuwanie i rozmiar:** prawy klik na pulpicie → **Tryb edycji**. Wtedy przeciągasz widget, a uchwyty na rogach zmieniają rozmiar. Wyjście: **Gotowe** na górze ekranu.

### Ustawienia

Prawy klik na widgecie → Konfiguruj:

| Ustawienie | Znaczenie |
|---|---|
| Adres serwera, Token | jak w aplikacji na PC |
| Pokazuj | całą tablicę (domyślnie) albo to samo co tablet |
| Ciemna tablica | ciemne albo jasne tło |
| Krycie tła | 0 = przezroczyste, 100 = pełne |

### Co pokazuje

- Tablicę, na której jesteś teraz na tablecie. Jeśli tablet nie był jeszcze połączony, pokazuje najnowszą tablicę.
- „Brak notatek”: połączony, ale tablica jest pusta.
- W prawym dolnym rogu, gdy coś jest nie tak: `łączę…` / `offline` (serwer nie odpowiada; widget sam próbuje co 1–30 s) albo `zły token` (popraw w Konfiguruj; wartość z `.env`).
- Gdy nie ma połączenia, widget jest pusty: niczego nie przechowuje lokalnie.

### Aktualizacja i usunięcie

- Aktualizacja: ponownie `./install-widget.sh` (bez `--add`, żeby nie dodać drugiej kopii). Jeśli widget nie pokaże zmian, wyloguj się i zaloguj ponownie.
- Usunięcie z pulpitu: prawy klik na widgecie → **Usuń**.
- Odinstalowanie: `kpackagetool6 -t Plasma/Applet -r io.github.marlenamakosza.livequill`.

## 6. Aktualizacja po zmianach w kodzie

```bash
./build.sh                       # buduje wszystko i kopiuje do dist/
docker compose up -d --build     # nowy serwer
./install-desktop.sh             # nowa aplikacja na PC (zamknij starą przed startem nowej)
./install-widget.sh              # nowy widget
# APK zainstaluj na tablecie jak w punkcie 4
```

Jeśli zmieniał się moduł `shared/` (protokół), zaktualizuj wszystkie trzy części.

## 7. Kopia zapasowa

Wszystkie notatki są w jednym pliku: `data/ops.jsonl`.

```bash
cp data/ops.jsonl ~/backup-livequill-$(date +%F).jsonl
```

Odtwarzanie: zatrzymaj serwer (`docker compose down`), podmień plik i uruchom serwer ponownie.

## 8. Gdy coś nie działa

**Tablet pokazuje `offline`:**
1. Na komputerze sprawdź `curl http://localhost:8787/health`. Jeśli nie ma `ok`, sprawdź `docker compose ps` i `docker compose logs`.
2. Na tablecie otwórz w przeglądarce `http://<IP-komputera>:8787/health`. Jeśli nie ma `ok`, tablet jest w innej sieci, IP się zmieniło albo blokuje firewall.
3. Porównaj adres w ⚙ z aktualnym IP komputera (punkt 3).

**Status `zły token`:** wartość w ⚙ albo w Opcjach na PC musi być identyczna z `.env`. Po zmianie `.env` uruchom `docker compose up -d`.

**Okno na PC nie pokazuje kresek:** sprawdź status w prawym górnym rogu. Kliknij „Wszystko”, bo kreski mogą być poza widokiem. Upewnij się, że oglądasz tę samą tablicę co na tablecie (przyciski `<` `>`) albo włącz „śledź”.

**„Przypnij” nie trzyma okna na wierzchu:** sprawdzone na KDE Plasma pod Waylandem. W innych środowiskach użyj opcji menedżera okien („zawsze na wierzchu”).

**`./build.sh`: permission denied przy Dockerze:** użytkownik nie jest w grupie `docker` albo nie wylogowałaś się po `usermod` (punkt 1).

## 9. Poza siecią domową

Zainstaluj Tailscale na komputerze i tablecie, a w ⚙ wpisz adres komputera z Tailscale (`100.x.y.z`). Dla publicznego serwera postaw go za reverse proxy z TLS i wpisz `https://host`. Tablet przejdzie wtedy na `wss://`.

## 10. Jak działa synchronizacja

- Każda zmiana to operacja: dodaj tablicę, dodaj kreskę, zmaż kreskę, usuń tablicę. Operacje są tylko dopisywane, więc nie ma konfliktów.
- Tablet zapisuje każdą operację najpierw do SQLite: do tabeli `ops` i do kolejki `outbox`. Rysowanie nigdy nie czeka na sieć.
- Gdy aplikacja jest otwarta, `SyncClient` trzyma otwarty WebSocket. Łączy się ponownie co 1–30 s i od razu po każdej zmianie sieci, a potem wysyła całą kolejkę.
- Serwer zapisuje operacje do `ops.jsonl`, robi fsync i dopiero wtedy potwierdza (ack). Tablet usuwa z kolejki tylko potwierdzone wpisy. Powtórzone wysyłki serwer ignoruje.
- Gdy aplikacja schodzi do tła z niepustą kolejką, przejmuje ją WorkManager. Wysyła przez `POST /api/push`, gdy tylko pojawi się sieć.
- Kreski w trakcie pisania (`live`, około 60 wiadomości/s) idą prosto do komputera i nie są zapisywane. Gotowa kreska zawsze dochodzi osobno jako operacja.
- Cofnięcie kreski to jej zmazanie. Przywrócenie dodaje kopię z nowym identyfikatorem.

## Kod

```
shared/   protokół (Op, Msg), NoteModel, wygląd pióra i kolory: wspólne dla wszystkich części
server/   Ktor (CIO), OpStore = log tylko do dopisywania, Hub = rozsyłanie przez WebSocket
android/  InkView (rysowanie, gesty), History (cofnij/przywróć), NoteDb (SQLite),
          SyncClient (WebSocket), SyncWorker (WorkManager)
desktop/  Compose Desktop
plasmoid/ widget KDE Plasma 6 (QML, tylko podgląd)
docker/   obraz budujący (JDK 21 + Android SDK + Gradle)
```

## Wydawanie nowej wersji

1. Ustaw `appVersion` w `gradle.properties` (np. `0.4.0`) i zrób commit.
2. Wypchnij tag:
   ```bash
   git tag v0.4.0
   git push origin v0.4.0
   ```
3. GitHub Actions ([workflow](.github/workflows/release.yml)) zbuduje i podpisze APK, spakuje aplikację na PC, widget i serwer, wypchnie obraz serwera do GHCR i opublikuje Release. Postęp widać w zakładce Actions.

Wersje zaczynające się od `0.` są oznaczane jako pre-release. Ręczne uruchomienie workflow (Actions → Release → Run workflow) tylko buduje; pliki trafiają do artefaktów uruchomienia.

Klucz do podpisu APK leży w sekretach repozytorium (`LIVEQUILL_KEYSTORE_BASE64`, `LIVEQUILL_STORE_PASSWORD`, `LIVEQUILL_KEY_ALIAS`, `LIVEQUILL_KEY_PASSWORD`). Lokalna kopia jest w `~/.config/livequill-signing/`. Zrób jej kopię zapasową: APK podpisane innym kluczem nie zainstaluje się na poprzednią wersję.

## Pomysły na dalej

- Jeszcze mniejsze opóźnienie pióra: rysowanie z pominięciem bufora klatki (`androidx.graphics` front-buffer) i `MotionEventPredictor`.
- Eksport tablicy do PDF/SVG albo do `.xopp` (Xournal++).

## Przejście z HandNotes

Do wersji 0.3.0 projekt nazywał się HandNotes. Nowa nazwa pakietu Androida (`io.github.marlenamakosza.livequill`) oznacza dla Androida nową aplikację:
1. Zainstaluj LiveQuill (obok starej HandNotes).
2. W starej HandNotes sprawdź, czy status to `● online` bez zmian w kolejce.
3. Odinstaluj HandNotes. W LiveQuill wpisz w ⚙ adres i token; notatki wrócą z serwera.

Na PC dalej działają `HANDNOTES_TOKEN` i `HANDNOTES_DATA`, a aplikacja na PC czyta stare ustawienia z `~/.config/handnotes/`, dopóki nie zapisze nowych. Starą instalację w `~/.local/opt/handnotes` możesz usunąć, gdy LiveQuill działa. Przy Dockerze uruchom raz `docker compose up -d --build --remove-orphans`, żeby podmienić stary kontener `handnotes`.

## Licencja

[MIT](LICENSE)
