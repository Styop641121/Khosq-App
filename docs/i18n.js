// The words of the page in Armenian, English and Russian. Armenian (the app's own language) is the default when the browser speaks
// something else. The words of the chat screens are in words.js.
import { MORE } from "./words.js";

const T = {
  hy: {
    tagline: "Քո զրույցները համակարգչում՝ նույն անվտանգ գաղտնագրմամբ",
    loginTitle: "Մուտք QR կոդով",
    step1: "Բացիր KHOSQ-ը հեռախոսում",
    step2: "Կարգավորումներ → Անվտանգություն → «Կապել բրաուզեր»",
    step3: "Ուղղիր հեռախոսը այս կոդին",
    step4: "Համոզվիր, որ հեռախոսի վրա նույն թիվն է՝",
    waiting: "Սպասում եմ հեռախոսին…",
    linking: "Կապվում է…",
    refresh: "Նոր կոդ",
    changes: "Կոդը ինքնաբերաբար թարմանում է",
    privacy: "Քո անձնական բանալին այս բրաուզերին հասնում է գաղտնագրված և պահվում է միայն այստեղ։ «Դուրս գալը» այն ջնջում է այս համակարգչից։",
    errServer: "Սերվերին չհաջողվեց միանալ. ստուգիր համացանցը",
    errOff: "Վեբ մուտքը սերվերի վրա դեռ միացված չէ",
    errPacket: "Հեռախոսի տվյալները չբացվեցին. սկանավորիր նոր կոդը",
    errStorage: "Բրաուզերը չի թույլ տալիս պահել տվյալներ. փակ (private) պատուհանում էջը չի աշխատի",
    errSession: "Մուտքը չհաջողվեց. նորից փորձիր",
    retry: "Նորից",
    homeTitle: "Դու մուտք ես գործել",
    homeNext: "Զրույցները, հաղորդագրությունները և մնացածը կհայտնվեն հաջորդ տարբերակներում։ Հեռախոսը մնում է քո հիմնական սարքը։",
    ok1: "Մուտքը հաստատված է",
    ok2: "Գաղտնագրման բանալին այս բրաուզերում է",
    ok3: "Բանալին համընկնում է քո հրապարակած բանալու հետ",
    bad3: "Բանալին չի համընկնում հրապարակածի հետ. վերակապիր բրաուզերը",
    keyId: "Բանալու նշանը",
    logout: "Դուրս գալ",
    logoutAsk: "Դուրս գա՞լ։ Բանալին կջնջվի այս համակարգչից։",
    cancel: "Չեղարկել",
  },
  en: {
    tagline: "Your chats on a computer, with the same end-to-end encryption",
    loginTitle: "Sign in with a QR code",
    step1: "Open KHOSQ on your phone",
    step2: "Settings → Security → “Link a browser”",
    step3: "Point the phone at this code",
    step4: "Make sure the phone shows the same number:",
    waiting: "Waiting for the phone…",
    linking: "Linking…",
    refresh: "New code",
    changes: "The code refreshes by itself",
    privacy: "Your private key reaches this browser encrypted and stays only here. “Log out” deletes it from this computer.",
    errServer: "Could not reach the server. Check your internet",
    errOff: "Web sign-in is not switched on on the server yet",
    errPacket: "The phone's data could not be opened. Scan the new code",
    errStorage: "The browser does not allow storing data. The page will not work in a private window",
    errSession: "Sign-in failed. Try again",
    retry: "Try again",
    homeTitle: "You are signed in",
    homeNext: "Chats, messages and the rest will appear in the next versions. The phone stays your main device.",
    ok1: "Signed in",
    ok2: "The encryption key is in this browser",
    ok3: "The key matches the one you published",
    bad3: "The key does not match the published one. Link the browser again",
    keyId: "Key fingerprint",
    logout: "Log out",
    logoutAsk: "Log out? The key will be deleted from this computer.",
    cancel: "Cancel",
  },
  ru: {
    tagline: "Ваши чаты на компьютере, с тем же сквозным шифрованием",
    loginTitle: "Вход по QR-коду",
    step1: "Откройте KHOSQ на телефоне",
    step2: "Настройки → Безопасность → «Привязать браузер»",
    step3: "Наведите телефон на этот код",
    step4: "Убедитесь, что на телефоне то же число:",
    waiting: "Жду телефон…",
    linking: "Привязываю…",
    refresh: "Новый код",
    changes: "Код обновляется автоматически",
    privacy: "Ваш личный ключ попадает в этот браузер в зашифрованном виде и хранится только здесь. «Выйти» удаляет его с этого компьютера.",
    errServer: "Не удалось связаться с сервером. Проверьте интернет",
    errOff: "Вход через веб на сервере ещё не включён",
    errPacket: "Данные телефона не открылись. Отсканируйте новый код",
    errStorage: "Браузер не позволяет хранить данные. В приватном окне страница не работает",
    errSession: "Не удалось войти. Попробуйте снова",
    retry: "Повторить",
    homeTitle: "Вы вошли",
    homeNext: "Чаты, сообщения и остальное появятся в следующих версиях. Телефон остаётся вашим главным устройством.",
    ok1: "Вход выполнен",
    ok2: "Ключ шифрования находится в этом браузере",
    ok3: "Ключ совпадает с опубликованным вами",
    bad3: "Ключ не совпадает с опубликованным. Привяжите браузер заново",
    keyId: "Отпечаток ключа",
    logout: "Выйти",
    logoutAsk: "Выйти? Ключ будет удалён с этого компьютера.",
    cancel: "Отмена",
  },
};

for (const code of Object.keys(MORE)) Object.assign(T[code], MORE[code]);

export const LANGS = [["hy", "Հայերեն"], ["en", "English"], ["ru", "Русский"]];
let current = "hy";

export function startLang() {
  let saved = "";
  try {
    saved = localStorage.getItem("khosq_lang") || "";
  } catch (e) {
    // storage blocked: the browser's language decides
  }
  const guess = (navigator.language || "hy").slice(0, 2).toLowerCase();
  current = T[saved] ? saved : T[guess] ? guess : "hy";
  document.documentElement.lang = current;
  return current;
}

export function setLang(code) {
  if (!T[code]) return;
  current = code;
  document.documentElement.lang = code;
  try {
    localStorage.setItem("khosq_lang", code);
  } catch (e) {
    // the choice just does not outlive the tab
  }
}

export const lang = () => current;
export const t = (key) => T[current][key] ?? T.en[key] ?? key;
/** A phrase with {0}, {1} filled in. */
export const tf = (key, ...args) => t(key).replace(/\{(\d)\}/g, (m, i) => (args[Number(i)] ?? ""));
