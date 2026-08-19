export function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) {
    return;
  }

  // При обновлении новый worker забирает контроль сразу (skipWaiting +
  // clients.claim в sw.js) — перезагружаем страницу, чтобы подхватить
  // свежий index.html и не застрять на старой версии.
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    window.location.reload();
  });

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js');
  });
}
