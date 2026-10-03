# F.INC – local music player (Android, Jetpack Compose)

## Release from Termux
```
pkg install git
cd finc
git init && git add . && git commit -m "F.INC v1"
git branch -M main
git remote add origin https://github.com/YOUR_USER/finc.git
git push -u origin main        # password = GitHub personal access token
git tag v1.0.0 && git push origin v1.0.0
```
GitHub Actions builds the APK and attaches `F.INC.apk` to the Release (Releases tab).
