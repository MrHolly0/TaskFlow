import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/app/components/ui/alert-dialog';
import type { MergeConflictResponse } from '@/lib/hooks/useIdentities';

type Props = {
  conflict: MergeConflictResponse | null;
  merging: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

function describeCounts(conflict: MergeConflictResponse): string {
  const parts: string[] = [];
  if (conflict.tasks > 0) parts.push(`${conflict.tasks} задач`);
  if (conflict.groups > 0) parts.push(`${conflict.groups} групп`);
  if (conflict.tags > 0) parts.push(`${conflict.tags} меток`);
  if (parts.length === 0) return 'Эта учётка уже привязана к другому аккаунту без данных.';
  return `В той учётке ${parts.join(', ')}.`;
}

// Отдельный диалог, а не автоматический перенос: доказательство владения
// почтой/Telegram и согласие на слияние двух учёток — разные вещи. Без
// «не показывать больше» — каждое слияние необратимо по-своему, спрашивать
// нужно каждый раз.
export function MergeConflictDialog({ conflict, merging, onConfirm, onCancel }: Props) {
  return (
    <AlertDialog open={conflict !== null} onOpenChange={(open) => { if (!open) onCancel(); }}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Этот способ входа уже используется</AlertDialogTitle>
          <AlertDialogDescription>
            {conflict && describeCounts(conflict)} Перенести их в эту учётку? Прежняя учётка после
            переноса станет недоступна — войти в неё будет нечем.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={merging}>Отмена</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} disabled={merging}>
            {merging ? 'Переносим...' : 'Перенести'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
