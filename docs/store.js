// Where the page keeps the identity key. It is stored as a key object the browser can use but never show (not extractable),
// next to the account id and the public key. Nothing else is kept here.
const DB = "khosq-web";
const STORE = "identity";

function open() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

const run = async (mode, fn) => {
  const db = await open();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, mode);
      const req = fn(tx.objectStore(STORE));
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
    return (await run("readonly", (s) => s.get("me"))) || null;
  } catch (e) {
    return null;
  }
}

/** Throws when the browser refuses to store: the caller tells the person. */
export const saveIdentity = (id) => run("readwrite", (s) => s.put(id, "me"));

export async function clearIdentity() {
  try {
    await run("readwrite", (s) => s.delete("me"));
  } catch (e) {
    // nothing was there
  }
}
