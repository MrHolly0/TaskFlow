import { IconMicrophone, IconSend, IconAlertTriangle } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { cn } from '@/lib/utils';
import type { VoiceRecordingController } from '@/lib/hooks/useVoiceRecording';

interface VoiceRecorderTriggerProps {
  recording: VoiceRecordingController;
  disabled?: boolean;
  className?: string;
}

export function VoiceRecorderTrigger({ recording, disabled, className }: VoiceRecorderTriggerProps) {
  const { mode, recorder, locked, isRecording } = recording;

  if (recorder.status === 'error') {
    return (
      <div className="flex items-center gap-1.5 text-xs text-destructive">
        <IconAlertTriangle className="h-4 w-4 flex-shrink-0" />
        <span className="min-w-0 break-words">{recorder.error}</span>
      </div>
    );
  }

  if (locked) {
    return (
      <Button type="button" size="icon" onClick={recording.finishSend} title="Отправить запись" className={className}>
        <IconSend className="h-4 w-4" />
      </Button>
    );
  }

  if (mode === 'SILENCE') {
    return (
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={recording.handleSilenceClick}
        title={isRecording ? 'Идёт запись — остановится сама после паузы' : 'Голосовое сообщение'}
        className={cn(isRecording && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  if (mode === 'TOGGLE') {
    return (
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={recording.handleToggleClick}
        title={isRecording ? 'Остановить и отправить' : 'Начать запись'}
        className={cn(isRecording && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  // ── HOLD ──
  return (
    <Button
      type="button"
      variant={isRecording ? 'default' : 'outline'}
      size="icon"
      disabled={disabled || recorder.status === 'requesting'}
      onPointerDown={recording.handleHoldPointerDown}
      onPointerMove={recording.handleHoldPointerMove}
      onPointerUp={recording.handleHoldPointerUp}
      onPointerCancel={recording.finishCancel}
      title="Зажми и говори"
      className={cn('touch-none select-none', isRecording && recording.cancelProgress < 1 && 'animate-pulse', className)}
      style={isRecording ? { transform: `translate(${recording.dragX}px, ${recording.dragY}px)` } : undefined}
    >
      <IconMicrophone className="h-4 w-4" />
    </Button>
  );
}
