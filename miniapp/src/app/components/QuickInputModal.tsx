import { useState, useRef, useEffect } from 'react';
import { IconSend, IconSparkles, IconArrowLeft, IconPaperclip, IconAlertTriangle } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useQuickAdd, useSetActionAccepted, useApplyProposal, useRejectProposal, Proposal } from '@/lib/hooks/useAssistant';
import { useEffectiveVoiceMode } from '@/lib/hooks/useVoiceMode';
import { useVoiceRecording } from '@/lib/hooks/useVoiceRecording';
import { ProposalCard } from '@/app/components/ProposalCard';
import { VoiceRecorderTrigger } from '@/app/components/VoiceRecorderTrigger';
import { VoiceRecordingBar } from '@/app/components/VoiceRecordingBar';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import { cn } from '@/lib/utils';

type Phase = 'input' | 'processing' | 'confirm' | 'done';

interface QuickInputModalProps {
  open: boolean;
  onClose: () => void;
}

function isRateLimited(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'response' in error
    && (error as { response?: { status?: number } }).response?.status === 429;
}

function errorMessage(error: unknown): string {
  if (isRateLimited(error)) {
    return 'Слишком много обращений подряд — подождите минуту и попробуйте снова.';
  }
  return 'Не получилось связаться с ассистентом. Попробуйте ещё раз.';
}

export function QuickInputModal({ open, onClose }: QuickInputModalProps) {
  const quickAdd = useQuickAdd();
  const setActionAccepted = useSetActionAccepted();
  const applyProposal = useApplyProposal();
  const rejectProposal = useRejectProposal();

  const [phase, setPhase] = useState<Phase>('input');
  const [text, setText] = useState('');
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [doneLabel, setDoneLabel] = useState('Добавлено!');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const voiceMode = useEffectiveVoiceMode();
  const recording = useVoiceRecording(voiceMode, (file) => send({ file }));

  useEffect(() => {
    if (open) {
      setPhase('input');
      setText('');
      setProposal(null);
      setError(null);
      setTimeout(() => textareaRef.current?.focus(), 100);
    }
  }, [open]);

  const closeWithSuccess = (label: string) => {
    setDoneLabel(label);
    setPhase('done');
    setTimeout(() => onClose(), 1500);
  };

  const send = (payload: { text?: string; file?: File }) => {
    setPhase('processing');
    setError(null);
    quickAdd.mutate(payload, {
      onSuccess: (result) => {
        if (result.applied) {
          closeWithSuccess('Добавлено!');
        } else {
          setProposal(result.proposal);
          setPhase('confirm');
        }
      },
      onError: (err) => {
        setError(errorMessage(err));
        setPhase('input');
      },
    });
  };

  const handleSubmit = () => {
    if (!text.trim()) return;
    send({ text: text.trim() });
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    send({ file });
  };

  const toggleAction = (ordinal: number, accepted: boolean) => {
    if (!proposal?.id) return;
    setActionAccepted.mutate(
      { proposalId: proposal.id, ordinal, accepted },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const applyCurrent = () => {
    if (!proposal?.id) return;
    applyProposal.mutate(proposal.id, {
      onSuccess: () => closeWithSuccess('Применено!'),
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const rejectCurrent = () => {
    if (!proposal?.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => { setPhase('input'); setProposal(null); },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const handleBack = () => { setPhase('input'); setProposal(null); setError(null); };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmit();
  };

  const handleClose = () => { onClose(); };

  return (
    <Dialog open={open} onOpenChange={(o: boolean) => !o && handleClose()}>
      <DialogContent
        className={cn(
          'sm:max-w-md w-full overflow-y-auto',
          'top-4 translate-y-0 max-h-[calc(100dvh-2rem)]',
          'sm:top-[50%] sm:translate-y-[-50%] sm:max-h-[90dvh]'
        )}
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            Что записать?
          </DialogTitle>
        </DialogHeader>

        <div className="min-w-0 space-y-4">
          <AnimatePresence mode="wait">
            {phase === 'input' && (
              <motion.div key="input" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                {recording.isRecording ? (
                  <VoiceRecordingBar recording={recording} />
                ) : (
                  <>
                    <Textarea
                      ref={textareaRef}
                      value={text}
                      onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => { setText(e.target.value); setError(null); }}
                      onKeyDown={handleKeyDown}
                      placeholder="Погулять с собакой, купить хлеба, позвонить маме..."
                      className="resize-none min-h-[100px] text-base"
                    />
                    <p className="text-xs text-muted-foreground">
                      Ассистент применит сразу, если это только новые задачи — иначе спросит подтверждения
                    </p>
                  </>
                )}
                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span className="min-w-0 break-words">{error}</span>
                  </div>
                )}
                <div className="flex flex-wrap gap-2">
                  <VoiceRecorderTrigger recording={recording} className="h-11 w-11 sm:size-9" />
                  {!recording.isRecording && (
                    <>
                      <Button
                        variant="outline"
                        onClick={() => fileInputRef.current?.click()}
                        className="gap-2 h-11 w-11 sm:w-auto sm:px-4"
                      >
                        <IconPaperclip className="h-4 w-4" />
                        <span className="hidden sm:inline">Файл</span>
                      </Button>
                      <input
                        ref={fileInputRef}
                        type="file"
                        accept="audio/*"
                        className="hidden"
                        onChange={handleFileSelect}
                      />
                      <Button onClick={handleSubmit} disabled={!text.trim()} className="flex-1 gap-2 h-11 min-w-[140px]">
                        <IconSend className="h-4 w-4" />
                        Отправить
                        <span className="hidden sm:inline text-xs opacity-60 ml-1">⌘↵</span>
                      </Button>
                    </>
                  )}
                </div>
              </motion.div>
            )}

            {phase === 'processing' && (
              <motion.div key="processing" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center justify-center py-8 space-y-3">
                <motion.div animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity, ease: 'linear' }} className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full" />
                <p className="text-muted-foreground text-sm">Обрабатываю обращение…</p>
              </motion.div>
            )}

            {phase === 'confirm' && proposal && (
              <motion.div key="confirm" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                {proposal.actions.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Не разобрал, что нужно сделать — попробуй переформулировать.
                  </p>
                ) : proposal.actions.some((a) => a.targetTaskId) ? (
                  <p className="text-xs text-muted-foreground">
                    Здесь есть изменения к существующим задачам — проверь перед подтверждением.
                  </p>
                ) : null}

                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span className="min-w-0 break-words">{error}</span>
                  </div>
                )}

                <ProposalCard
                  proposal={proposal}
                  onToggle={toggleAction}
                  onApply={applyCurrent}
                  onReject={rejectCurrent}
                  applying={applyProposal.isPending}
                  rejecting={rejectProposal.isPending}
                />

                <Button variant="outline" onClick={handleBack} className="gap-2 h-11 w-full">
                  <IconArrowLeft className="h-4 w-4" />
                  Назад
                </Button>
              </motion.div>
            )}

            {phase === 'done' && (
              <motion.div key="done" initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="flex flex-col items-center justify-center py-6 space-y-4">
                <motion.div initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: 'spring', duration: 0.5 }} className="w-14 h-14 bg-green-500 rounded-full flex items-center justify-center">
                  <IconSparkles className="h-7 w-7 text-white" />
                </motion.div>
                <p className="font-medium">{doneLabel}</p>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </DialogContent>
    </Dialog>
  );
}
