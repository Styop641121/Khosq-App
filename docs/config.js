// The address of the KHOSQ Worker (the same one the phone app uses). The Web API key and the project id are not kept here:
// the page asks the Worker for them (/link/config), so there is nothing to copy by hand.
export const WORKER = "https://khosq-push.styoptev.workers.dev";

// The Firebase web library, loaded from Google's own address.
export const FIREBASE = "https://www.gstatic.com/firebasejs/10.14.1/";

// How long one QR code is shown before a new one is made, and how often the page asks the Worker whether the phone has answered.
export const QR_LIFETIME_MS = 100000;
export const POLL_MS = 2500;
