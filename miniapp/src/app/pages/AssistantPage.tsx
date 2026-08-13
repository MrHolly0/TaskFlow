import { useState } from 'react';
import { IconSend, IconSparkles, IconAlertTriangle } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import {
  useSendAssistantMessage,
  useSetActionAccepted,
  useApplyProposal,
  useRejectProposal,
  Proposal,
  ApplyResult,
} from '@/lib/hooks/useAssistant';
import { useEffectiveVoiceMode } from '@/lib/hooks/useVoiceMode';
import { useVoiceRecording } from '@/lib/hooks/useVoiceRecording';
import { ProposalCard } from '@/app/components/ProposalCard';
import { VoiceRecorderTrigger } from '@/app/components/VoiceRecorderTrigger';
import { VoiceRecordingBar } from '@/app/components/VoiceRecordingBar';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import { Card } from '@/app/components/ui/card';

type Entry =
  | { kind: 'user'; text: string }
  | { kind: 'pending'; localId: string }
  | { kind: 'proposal'; proposal: Proposal; localId: string }
  | { kind: 'applied'; result: ApplyResult; localId: string }
  | { kind: 'rejected'; localId: string }
  | { kind: 'error'; message: string; localId: string };

let entryCounter = 0;
function nextId() {
  entryCounter += 1;
  return `e${entryCounter}`;
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

export function AssistantPage() {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [input, setInput] = useState('');
  const sendMessage = useSendAssistantMessage();
  const setActionAccepted = useSetActionAccepted();
  const applyProposal = useApplyProposal();
  const rejectProposal = useRejectProposal();
  const voiceMode = useEffectiveVoiceMode();
  const recording = useVoiceRecording(voiceMode, (file) => send({ file }));

  const replaceEntry = (localId: string, entry: Entry) => {
    setEntries((prev) => prev.map((e) => ('localId' in e && e.localId === localId ? entry : e)));
  };

  const send = (payload: { text?: string; file?: File }) => {
    const localId = nextId();
    const userText = payload.text ?? '🎤 Голосовое сообщение';
    setEntries((prev) => [...prev, { kind: 'user', text: userText }, { kind: 'pending', localId }]);
    setInput('');

    sendMessage.mutate(payload, {
      onSuccess: (proposal) => {
        replaceEntry(localId, { kind: 'proposal', proposal, localId });
      },
      onError: (error) => {
        replaceEntry(localId, { kind: 'error', message: errorMessage(error), localId });
      },
    });
  };

  const handleSubmit = () => {
    const text = input.trim();
    if (!text) return;
    send({ text });
  };

  const toggleAction = (proposal: Proposal, localId: string, ordinal: number, accepted: boolean) => {
    if (!proposal.id) return;
    setActionAccepted.mutate(
      { proposalId: proposal.id, ordinal, accepted },
      {
        onSuccess: (updated) => replaceEntry(localId, { kind: 'proposal', proposal: updated, localId }),
      }
    );
  };

  const apply = (proposal: Proposal, localId: string) => {
    if (!proposal.id) return;
    applyProposal.mutate(proposal.id, {
      onSuccess: (result) => replaceEntry(localId, { kind: 'applied', result, localId }),
      onError: () => replaceEntry(localId, { kind: 'error', message: errorMessage(undefined), localId }),
    });
  };

  const reject = (proposal: Proposal, localId: string) => {
    if (!proposal.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => replaceEntry(localId, { kind: 'rejected', localId }),
      onError: () => replaceEntry(localId, { kind: 'error', message: errorMessage(undefined), localId }),
    });
  };

  return (
    <div className="mx-auto flex h-full max-w-2xl flex-col">
      <div className="mb-4 flex items-center gap-2">
        <IconSparkles className="h-5 w-5 text-primary" />
        <h1 className="text-lg font-semibold">Ассистент</h1>
      </div>

      <div className="flex-1 space-y-3 overflow-y-auto pb-4">
        {entries.length === 0 && (
          <p className="text-sm text-muted-foreground">
            Расскажите, что нужно сделать — своими словами, как обычному человеку. Ассистент предложит, что изменить, и ничего не тронет без вашего подтверждения.
          </p>
        )}
        <AnimatePresence initial={false}>
          {entries.map((entry, i) => (
            <motion.div
              key={'localId' in entry ? entry.localId : `user-${i}`}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
            >
              {entry.kind === 'user' && (
                <div className="ml-auto max-w-[80%] break-words rounded-2xl rounded-br-sm bg-primary text-primary-foreground px-4 py-2 text-sm">
                  {entry.text}
                </div>
              )}

              {entry.kind === 'pending' && (
                <Card className="max-w-[85%] px-4 py-3 text-sm text-muted-foreground">
                  Обрабатываю обращение…
                </Card>
              )}

              {entry.kind === 'error' && (
                <Card className="max-w-[85%] border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive flex items-start gap-2">
                  <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <span className="min-w-0 break-words">{entry.message}</span>
                </Card>
              )}

              {entry.kind === 'rejected' && (
                <Card className="max-w-[85%] px-4 py-3 text-sm text-muted-foreground">
                  Предложение отклонено.
                </Card>
              )}

              {entry.kind === 'applied' && (
                <ApplyResultCard result={entry.result} />
              )}

              {entry.kind === 'proposal' && (
                <ProposalCard
                  proposal={entry.proposal}
                  onToggle={(ordinal, accepted) => toggleAction(entry.proposal, entry.localId, ordinal, accepted)}
                  onApply={() => apply(entry.proposal, entry.localId)}
                  onReject={() => reject(entry.proposal, entry.localId)}
                  applying={applyProposal.isPending}
                  rejecting={rejectProposal.isPending}
                />
              )}
            </motion.div>
          ))}
        </AnimatePresence>
      </div>

      <div className="flex items-end gap-2 border-t border-border pt-3">
        {recording.isRecording ? (
          <VoiceRecordingBar key="bar" recording={recording} />
        ) : (
          <Textarea
            key="input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            placeholder="Например: закрой молоко и позвони Марку завтра"
            className="min-h-16 max-h-32 resize-none"
          />
        )}
        <VoiceRecorderTrigger key="trigger" recording={recording} disabled={sendMessage.isPending} />
        {!recording.isRecording && (
          <Button key="send" size="icon" onClick={handleSubmit} disabled={!input.trim() || sendMessage.isPending} title="Отправить">
            <IconSend className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );
}

function statusLabel(status: string): string | null {
  switch (status) {
    case 'EXPIRED':
      return 'Предложение просрочено — прошло больше суток, ничего не применено.';
    case 'FAILED':
      return null; // отрисовывается отдельно, через clarification
    default:
      return null;
  }
}

function ApplyResultCard({ result }: { result: ApplyResult }) {
  const expiredLabel = statusLabel(result.status);
  const failures = result.outcomes.filter((o) => !o.success);

  return (
    <Card className="max-w-[85%] px-4 py-3 text-sm space-y-1.5">
      <p className="font-medium">
        {expiredLabel ?? `Применено ${result.appliedCount} из ${result.totalCount}.`}
      </p>
      {failures.length > 0 && (
        <ul className="space-y-1 text-muted-foreground">
          {failures.map((f) => (
            <li key={f.ordinal} className="break-words">
              {f.summary} — {f.error}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
