import { Link } from 'react-router-dom';
import { IconAlertTriangle } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { useIdentities } from '@/lib/hooks/useIdentities';

// Заметный призыв, не подсказка в углу настроек: от привязки почты зависит,
// сможет ли человек войти, когда кнопку Telegram спрячут по требованиям
// 406-ФЗ/199-ФЗ. Показывается на экране, который видно сразу при входе,
// и не прячется насовсем — только исчезает, когда почта появится в списке.
export function EmailBindBanner() {
  const { data: identities, isLoading, isError } = useIdentities();
  // Загрузка и ошибка — то же самое «не знаем»: банер утверждает «у тебя
  // только Telegram», а на деле мы просто не получили ответ. Молчать в
  // обоих случаях безопаснее, чем гадать.
  if (isLoading || isError || !identities) return null;
  if (identities.some((i) => i.provider === 'EMAIL')) return null;

  return (
    <div className="rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 flex items-center justify-between gap-4">
      <div className="flex items-center gap-3 min-w-0">
        <IconAlertTriangle className="h-5 w-5 shrink-0 text-amber-600 dark:text-amber-400" />
        <div className="min-w-0">
          <p className="text-sm font-medium">Привяжи почту</p>
          <p className="text-xs text-muted-foreground">
            Сейчас у тебя только Telegram — привяжи почту как запасной способ входа.
          </p>
        </div>
      </div>
      <Link to="/integrations" className="shrink-0">
        <Button size="sm">Привязать</Button>
      </Link>
    </div>
  );
}
