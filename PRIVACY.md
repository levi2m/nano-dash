# Privacy

Nano Dash collects nothing and sends nothing. The app declares no internet permission, so it cannot upload anything.

- **What it records:** your GPS track, phone motion sensors and pressure, and the camera's Bluetooth commands with their times, for the rides you start. A camera is named in the ride files by the last five characters of its Bluetooth address.
- **Where it stays:** in the app's own folder on your phone (`Android/data/<app>/files/rides`), so you can reach it over USB when troubleshooting. On Android 10 and older, another app that holds storage permission can read that folder; on Android 11 and later other apps cannot. Rides hold precise locations, so treat the files as personal data. Backups and device transfers are switched off for the app.
- **When it leaves:** only when you tap Share on a ride, or tap its route to open it in a maps app, and choose where to send it. The route is handed over as a GPX file; "Check for updates" only opens a web page in your browser. The app itself never uses the network.
- **Deleting:** Delete ride removes the folder and the temporary share copies (the route GPX and the export zip), which are kept in the app's cache. Uninstalling the app removes every ride.
- **Bluetooth:** scanning is used only to find the camera, not your location. The phone's pairing identifier is stored in the app's private preferences with the camera's address.
