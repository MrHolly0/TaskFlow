import { useEffect, useState } from 'react';

// formatDeadline — чистая функция от текущего момента: без этого хука она
// вызывается один раз при рендере и застывает («через 33 мин» не двигается),
// пока что-то постороннее не перерисует компонент. Тик ничего не хранит,
// только форсирует пере-рендер раз в минуту, пока владелец на экране —
// снимается вместе с ним при размонтировании.
export function useMinuteTick(): void {
  const [, forceRender] = useState(0);
  useEffect(() => {
    const id = setInterval(() => forceRender((n) => n + 1), 60000);
    return () => clearInterval(id);
  }, []);
}
