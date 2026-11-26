// Where the page keeps things in this browser (IndexedDB):
//  - identity: the private key, as a key object the browser can use but never show (not extractable), with the account id and public key;
//  - pins:     the public key of each person, as first seen (so that a later swap on the server is noticed, like on the phone);
//  - photos:   profile pictures already downloaded, with their version, so the list does not ask the server again.
// "Log out" removes all of it.
const DB = "khosq-web";
const STORES = ["identity", "pins", "photos"];

function open() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 2);
    r.onupgradeneeded = () => {
      for (const s of STORES) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s);
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

const run = async (store, mode, fn) => {
  const db = await open();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const req = fn(tx.objectStore(store));
      tx.oncomplete = () => resolve(req ? req.result : undefined);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
};

/** {uid, pub, key (CryptoKey), at} or null when nothing is stored (or the browser does not let us look). */
export async function loadIdentity() {
  try {
    return (await run("identity", "readonly", (s) => s.get("me"))) || null;
  } catch (e) {
    return null;
  }
}

/** Throws when the browser refuses to store: the caller tells the person. */
export const saveIdentity = (id) => run("identity", "readwrite", (s) => s.put(id, "me"));

/** Removes the key and everything else this browser kept. */
export async function clearIdentity() {
  for (const s of STORES) {
    try {
      await run(s, "readwrite", (st) => st.clear());
    } catch (e) {
      // nothing was there
    }
  }
}

/** Keys of other people, as first seen. The cache must never stop the page: a failure is a miss. */
export const pins = {
  get: async (uid) => {
    try {
      return await run("pins", "readonly", (s) => s.get(uid));
    } catch (e) {
      return undefined;
    }
  },
  set: async (uid, text) => {
    try {
      await run("pins", "readwrite", (s) => s.put(text, uid));
    } catch (e) {
      // the key is used for this visit only
    }
  },
};

/** {v, bytes} or undefined. */
export async function getPhoto(id) {
  try {
    return await run("photos", "readonly", (s) => s.get(id));
  } catch (e) {
    return undefined;
  }
}

export async function putPhoto(id, v, bytes) {
  try {
    await run("photos", "readwrite", (s) => s.put({ v, bytes }, id));
  } catch (e) {
    // shown now, asked for again next time
  }
}
