// Наличие сенсорного ввода, а не ширина окна — узкое окно на компьютере всё ещё
// компьютер, а удержание для записи голоса там неуместно.
export function isTouchDevice(): boolean {
  return 'ontouchstart' in window || navigator.maxTouchPoints > 0;
}
