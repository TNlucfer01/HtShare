# HtShare

Share a folder from your computer to any phone or laptop on the same network. Pick a folder, press **Start sharing**, scan the QR code. No app is needed on the other device, only its browser.

![HtShare desktop app](screenshots/main-window.png)

## Features

- **QR code connect**: scan with the phone camera, or copy the link
- **HTTPS by default**: TLS with a self-signed certificate generated at start-up, with HTTP available as a switch
- **Auto-stop**: sharing stops after 5 minutes with no activity (optional)
- **Live stats**: requests, downloads, connected devices, time until auto-stop
- **Network check**: detects the active interface and warns when there isn't one
- **Free port lookup**: starts at 8443 (HTTPS) or 8080 (HTTP) and moves up if the port is busy
- **Dark and light themes** in the desktop app; the phone page follows the phone's own setting

## The phone side

The connected device gets a plain, fast file browser: folders first, file sizes, one tap to download.

![Phone view, light and dark](screenshots/phone.png)

## Install

### AppImage (Linux)

```bash
chmod +x HtShare-x86_64.AppImage
./HtShare-x86_64.AppImage
```

The AppImage bundles its own Java runtime, so nothing else needs to be installed.

### From source

Requires Java 17+ and Maven 3.6+.

```bash
git clone https://github.com/TNlucfer01/htshare.git
cd htshare
mvn javafx:run          # run
mvn clean package       # fat jar at target/htshare-1.0.0.jar
```

## Using HTTPS

The certificate is self-signed, so the phone's browser shows a warning the first time. Tap **Advanced → Proceed** to continue. The connection is still encrypted; the warning only means the certificate isn't from a public authority.

- Keep HTTPS on for anything private, or on shared or public Wi-Fi.
- Turn it off on a trusted home network if you want the fastest transfers and no warning.

Anyone on the same network who has the link can browse the shared folder. There is no password yet, so share only what you mean to, and don't expose HtShare to the internet.

## Project structure

```
src/main/java/com/htshare/
├── MainApp.java                 entry point, window, fonts
├── ui/MainController.java       desktop UI logic
├── server/
│   ├── HttpsFileServer.java     file server used by the app (NanoHTTPD, HTTP + HTTPS)
│   ├── ConnectionMonitor.java   stats and idle auto-stop
│   ├── Helper.java              phone page rendering, MIME types, ports
│   └── HttpFileServer.java      older JDK-HttpServer implementation (not used by the UI)
└── util/                        network detection, QR codes, certificate generation

src/main/resources/
├── fxml/main.fxml               desktop layout
├── css/base.css                 desktop components and layout
├── css/dark-theme.css           dark palette  (-hs-* colors)
├── css/light-theme.css          light palette (-hs-* colors)
├── template/home.html           phone page
└── fonts/                       Inter (SIL OFL 1.1)
```

To change colors, edit only the `-hs-*` variables in `dark-theme.css` / `light-theme.css`; every component in `base.css` reads from them.

## Troubleshooting

**Phone can't connect**: both devices must be on the same Wi-Fi. Check that your firewall allows the port shown in the link (8443 or 8080 by default):

```bash
sudo ufw allow 8443/tcp   # or 8080 for HTTP
```

**Port already in use**: HtShare picks the next free port automatically and tells you which one it used.

**No network detected**: connect to Wi-Fi or Ethernet, then press the refresh icon in the status bar.

## Roadmap

- [ ] Upload from phone to computer
- [ ] Password / PIN protection
- [ ] Share multiple folders
- [ ] Drag and drop a folder onto the window
- [ ] Transfer history
- [ ] System tray

## Built with

[JavaFX](https://openjfx.io/) · [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) · [ZXing](https://github.com/zxing/zxing) · [Ikonli](https://kordamp.org/ikonli/) (Feather icons) · [Inter](https://rsms.me/inter/)

## License

MIT. The Inter font is under the SIL Open Font License, see `src/main/resources/fonts/OFL.txt`.
