// Renders the iOS app icon (1024 px, no transparency) from the One roof drawing the Android icon is made from:
//   node tools/ios-icon.js docs/screens/mocks/app-icon-one-roof.html iosApp/Talk/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
// Needs Playwright (npm i playwright) for its Chromium: the soft shadows need a browser to draw them.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [src, out] = process.argv.slice(2);
  let html = fs.readFileSync(src, 'utf8');
  // Android's adaptive icon is a 108 canvas with its picture in the middle; iOS shows the whole square, so this takes
  // the middle 90 of it.
  html = html.replace('width="432" height="432" viewBox="0 0 108 108"', 'width="1024" height="1024" viewBox="9 11 90 90"');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1024, height: 1024 }, deviceScaleFactor: 1 });
  await page.setContent(html);
  await page.screenshot({ path: out, clip: { x: 0, y: 0, width: 1024, height: 1024 }, omitBackground: false });
  await browser.close();
})();
