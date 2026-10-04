# Third-party components

## Local HEIC receipt conversion

The unmodified `heic-to` package version 1.6.5 is distributed with the web application under LGPL-3.0 (or later under its supplied notice). Its full license is shipped at `/licenses/heic-to-LGPL-3.0.txt`, with the companion GPL terms at `/licenses/GPL-3.0.txt`. The [upstream source and build instructions](https://github.com/hoppergee/heic-to) identify the included libheif decoder and its dependencies. Conversion uses the CSP build in a locally served worker; no CDN or remote conversion service receives receipts.

The application source and package manifest identify the integration in `receipt-conversion.ts` and `receipt-conversion.worker.ts`. To replace or modify the library, install the desired compatible package/source version in `frontend`, retain its required notices, then run `npm run build` and the HEIC acceptance checks. There is no browser restriction on replacing the library. Keep this notice and the complete upstream license when redistributing the web assets.

The HEIC test fixture is a generated 64×64 solid teal image containing no personal information. Its PNG source was generated locally and encoded with Ubuntu's libheif test tools. The production browser acceptance uses this synthetic fixture exclusively.
