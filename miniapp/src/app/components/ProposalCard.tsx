import { IconCheck, IconX, IconAlertTriangle } from '@tabler/icons-react';
import { Proposal } from '@/lib/hooks/useAssistant';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { cn } from '@/lib/utils';

export function ProposalCard({
  proposal,
  onToggle,
  onApply,
  onReject,
  applying,
  rejecting,
  className,
}: {
  proposal: Proposal;
  onToggle: (ordinal: number, accepted: boolean) => void;
  onApply: () => void;
  onReject: () => void;
  applying: boolean;
  rejecting: boolean;
  className?: string;
}) {
  const isDegraded = proposal.status === 'FAILED' && !proposal.id;
  const isClarification = !proposal.actions.length && !!proposal.clarification && !isDegraded;

  if (isDegraded) {
    return (
      <Card className={cn('px-4 py-3 text-sm flex items-start gap-2 border-amber-400/40 bg-amber-50 dark:bg-amber-950/30', className)}>
        <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0 text-amber-600" />
        <span className="min-w-0 break-words">{proposal.clarification ?? 'Не удалось разобрать сообщение — сохранил его как отдельную задачу целиком.'}</span>
      </Card>
    );
  }

  if (isClarification) {
    return (
      <Card className={cn('px-4 py-3 text-sm break-words', className)}>
        {proposal.clarification}
      </Card>
    );
  }

  if (!proposal.actions.length) {
    return (
      <Card className={cn('px-4 py-3 text-sm text-muted-foreground', className)}>
        Не нашел, что предложить по этому сообщению.
      </Card>
    );
  }

  return (
    <Card className={cn('px-4 py-3 space-y-3', className)}>
      <p className="text-sm font-medium">Предлагаю:</p>
      <div className="space-y-1.5">
        {proposal.actions.map((action) => (
          <label
            key={action.ordinal}
            className="flex items-start gap-2 text-sm cursor-pointer select-none"
          >
            <input
              type="checkbox"
              checked={action.accepted}
              onChange={(e) => onToggle(action.ordinal, e.target.checked)}
              className="mt-0.5 flex-shrink-0"
            />
            <span className={cn('min-w-0 break-words', !action.accepted && 'text-muted-foreground line-through')}>
              {action.summary}
            </span>
          </label>
        ))}
      </div>
      <div className="flex gap-2 pt-1">
        <Button size="sm" onClick={onApply} disabled={applying || rejecting} className="flex-1 gap-1.5">
          <IconCheck className="h-3.5 w-3.5" />
          Применить
        </Button>
        <Button size="sm" variant="outline" onClick={onReject} disabled={applying || rejecting} className="flex-1 gap-1.5">
          <IconX className="h-3.5 w-3.5" />
          Отклонить
        </Button>
      </div>
    </Card>
  );
}
