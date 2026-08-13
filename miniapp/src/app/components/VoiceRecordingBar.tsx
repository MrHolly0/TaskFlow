import { IconMicrophone, IconX } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import type { VoiceRecordingController } from '@/lib/hooks/useVoiceRecording';

function formatElapsed(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function hintFor(recording: VoiceRecordingController): string {
  const { mode, locked } = recording;
  if (mode === 'HOLD') {
    return locked ? 'Запись продолжается — нажми кнопку, чтобы отправить' : 'Веди влево — отмена, вверх — закрепить';
  }
  if (mode === 'SILENCE') return 'Остановится сама после паузы';
  return 'Нажми кнопку ещё раз, чтобы отправить';
}

export function VoiceRecordingBar({ recording, className }: { recording: VoiceRecordingController; className?: string }) {
  return (
    <div
      className={cn(
        'flex min-w-0 flex-1 items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2',
        className
      )}
    >
      <span className="relative flex h-6 w-6 flex-shrink-0 items-center justify-center">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-destructive/40" />
        <IconMicrophone className="relative h-4 w-4 text-destructive" />
      </span>
      <span className="flex-shrink-0 text-sm font-medium tabular-nums">{formatElapsed(recording.elapsedMs)}</span>
      <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">{hintFor(recording)}</span>
      <button
        type="button"
        onClick={recording.finishCancel}
        className="flex-shrink-0 rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
        title="Отменить запись"
      >
        <IconX className="h-4 w-4" />
      </button>
    </div>
  );
}
