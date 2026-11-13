// Microsoft Clarity for the website (not the app): visit length, bounces, pages, scroll and click heatmaps.
// Off unless the build has a CLARITY_ID (the repository variable of the same name in the Pages workflow).
// Skipped on localhost so `npm run dev` and `npm run preview` don't show up in the numbers.
const id = (process.env.CLARITY_ID ?? "").trim();
export const CLARITY_ID = /^[a-z0-9]+$/i.test(id) ? id : "";

export const clarityScript = CLARITY_ID
  ? `if (!/^(localhost|127\\.0\\.0\\.1|\\[::1\\])$/.test(location.hostname)) {
  (function(c,l,a,r,i,t,y){
    c[a]=c[a]||function(){(c[a].q=c[a].q||[]).push(arguments)};
    t=l.createElement(r);t.async=1;t.src="https://www.clarity.ms/tag/"+i;
    y=l.getElementsByTagName(r)[0];y.parentNode.insertBefore(t,y);
  })(window, document, "clarity", "script", "${CLARITY_ID}");
}`
  : "";
