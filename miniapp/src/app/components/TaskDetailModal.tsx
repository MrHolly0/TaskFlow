import { useState, useEffect } from 'react';
import { IconTrash, IconClock, IconBell, IconBellPlus, IconX } from '@tabler/icons-react';
import { toZonedTime, fromZonedTime } from 'date-fns-tz';
import { Priority, Status } from '@/lib/store';
import {
  useUpdateTask,
  useDeleteTask,
  useGroups,
  useTaskReminders,
  useAddReminder,
  useCancelReminder,
} from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { buildReminderPresets, isPastReminderTime } from '@/lib/reminderPresets';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { cn, formatReminderTime } from '@/lib/utils';

const PRIORITY_OPTIONS: { value: Priority; label: string; color: string }[] = [
  { value: 'URGENT', label: 'Срочно', color: 'text-red-500' },
  { value: 'HIGH', label: 'Важно', color: 'text-orange-500' },
  { value: 'MEDIUM', label: 'Средний', color: 'text-blue-500' },
  { value: 'LOW', label: 'Когда будет время', color: 'text-muted-foreground' },
];

const STATUS_OPTIONS: { value: Status; label: string }[] = [
  { value: 'TODO', label: 'К выполнению' },
  { value: 'IN_PROGRESS', label: 'В работе' },
  { value: 'DONE', label: 'Готово' },
  { value: 'CANCELLED', label: 'Отменено' },
];

export interface TaskInput {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  group?: string;
  groupName?: string;
  groupId?: string;
  estimatedTime?: number;
  estimateMinutes?: number;
}

interface TaskDetailModalProps {
  task: TaskInput | null;
  open: boolean;
  onClose: () => void;
}

// d.getFullYear()/getHours() и т.п. здесь — не браузерный локальный час:
// toZonedTime подменяет представление даты так, что стандартные геттеры
// отдают компоненты времени в переданном поясе, а не в поясе устройства.
function isoToZonedDate(iso: string | undefined, timezone: string): string {
  if (!iso) return '';
  const d = toZonedTime(iso, timezone);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function isoToZonedTime(iso: string | undefined, timezone: string): string {
  if (!iso) return '';
  const d = toZonedTime(iso, timezone);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

// Обратное преобразование: дата/время, введённые пользователем как есть
// (без указания пояса), интерпретируются как момент в его домашнем поясе.
function zonedInputToIso(date: string, time: string, timezone: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const wallClock = new Date(year, month - 1, day, hour, minute);
  return fromZonedTime(wallClock, timezone).toISOString();
}

function AddReminderForm({
  taskId,
  timezone,
  onDone,
}: {
  taskId: string;
  timezone: string;
  onDone: () => void;
}) {
  const { mutateAsync: addReminder, isPending } = useAddReminder();
  const [date, setDate] = useState('');
  const [time, setTime] = useState('');
  const [error, setError] = useState('');

  const presets = buildReminderPresets(new Date(), timezone);

  const submit = async (fireAt: Date) => {
    if (isPastReminderTime(fireAt)) {
      setError('Это время уже прошло');
      return;
    }
    await addReminder({ taskId, fireAt: fireAt.toISOString() });
    onDone();
  };

  const handleManualSubmit = () => {
    if (!date || !time) return;
    submit(new Date(zonedInputToIso(date, time, timezone)));
  };

  return (
    <div className="space-y-2 rounded-lg border border-border/60 p-3">
      <div className="flex flex-wrap gap-1.5">
        {presets.map((p) => (
          <Button
            key={p.key}
            type="button"
            variant="outline"
            size="sm"
            className="h-8 text-xs"
            disabled={isPending}
            onClick={() => submit(p.fireAt)}
          >
            {p.label}
          </Button>
        ))}
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <Input type="date" value={date} onChange={(e) => { setDate(e.target.value); setError(''); }} className="h-9 text-sm" />
        <Input
          type="time"
          value={time}
          onChange={(e) => { setTime(e.target.value); setError(''); }}
          className="h-9 text-sm"
          disabled={!date}
        />
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
      <div className="flex gap-2">
        <Button type="button" size="sm" className="h-8" disabled={!date || !time || isPending} onClick={handleManualSubmit}>
          Добавить
        </Button>
        <Button type="button" variant="ghost" size="sm" className="h-8" onClick={onDone}>
          Отмена
        </Button>
      </div>
    </div>
  );
}

export function TaskDetailModal({ task, open, onClose }: TaskDetailModalProps) {
  const { mutateAsync: updateTask } = useUpdateTask();
  const { mutate: deleteTask } = useDeleteTask();
  const { data: groups = [] } = useGroups();
  const { timezone, isReady: timezoneReady } = useUserTimezone();
  const { data: reminders = [] } = useTaskReminders(task?.id, open);
  const { mutate: cancelReminder } = useCancelReminder();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<Priority>('MEDIUM');
  const [status, setStatus] = useState<Status>('TODO');
  const [selectedGroupId, setSelectedGroupId] = useState<string>('');
  const [deadlineDate, setDeadlineDate] = useState('');
  const [deadlineTime, setDeadlineTime] = useState('');
  const [estimatedTime, setEstimatedTime] = useState('');
  const [saving, setSaving] = useState(false);
  const [showAddReminder, setShowAddReminder] = useState(false);

  useEffect(() => {
    if (task) {
      setShowAddReminder(false);
      setTitle(task.title);
      setDescription(task.description ?? '');
      setPriority((task.priority as Priority) ?? 'MEDIUM');
      setStatus((task.status as Status) ?? 'TODO');
      const estimate = task.estimateMinutes ?? task.estimatedTime;
      setEstimatedTime(estimate ? String(estimate) : '');
      // resolve initial group id
      if (task.groupId) {
        setSelectedGroupId(task.groupId);
      } else {
        const groupLabel = task.groupName ?? task.group ?? '';
        const found = groups.find(g => g.name === groupLabel);
        setSelectedGroupId(found?.id ?? '');
      }
    }
  }, [task?.id, groups.length]);

  // Отдельный эффект — срок зависит от часового пояса из настроек, который
  // может подгрузиться позже остального. Пока не готов, поля остаются пустыми,
  // а не показывают дату/время, посчитанные в поясе браузера.
  useEffect(() => {
    if (task && timezoneReady) {
      setDeadlineDate(isoToZonedDate(task.deadline, timezone));
      setDeadlineTime(isoToZonedTime(task.deadline, timezone) || '20:59');
    }
  }, [task?.id, timezoneReady, timezone]);

  const taskId = task?.id;

  const handleSave = async () => {
    if (!taskId) return;
    setSaving(true);
    try {
      await updateTask({
        id: taskId,
        title: title.trim() || undefined,
        description: description || undefined,
        priority,
        status,
        deadline: deadlineDate ? zonedInputToIso(deadlineDate, deadlineTime || '20:59', timezone) : undefined,
        groupId: selectedGroupId || undefined,
        estimateMinutes: estimatedTime ? parseInt(estimatedTime) : undefined,
      });
      onClose();
    } catch {
      onClose();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = () => {
    if (!taskId) return;
    deleteTask(taskId);
    onClose();
  };

  if (!task) return null;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        className={cn(
          'sm:max-w-lg w-full overflow-y-auto',
          'top-4 translate-y-0 max-h-[calc(100dvh-2rem)]',
          'sm:top-[50%] sm:translate-y-[-50%] sm:max-h-[90dvh]'
        )}
      >
        <DialogHeader>
          <DialogTitle className="sr-only">Задача</DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
          {/* Title */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Задача
            </label>
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="text-base font-medium h-11"
              placeholder="Название задачи"
            />
          </div>

          {/* Description */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Описание
            </label>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Что нужно сделать..."
              className="resize-none min-h-[80px]"
            />
          </div>

          {/* Priority + Status row */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Приоритет
              </label>
              <Select value={priority} onValueChange={(v) => setPriority(v as Priority)}>
                <SelectTrigger className="h-10">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITY_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      <span className={cn('font-medium', opt.color)}>{opt.label}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Статус
              </label>
              <Select value={status} onValueChange={(v) => setStatus(v as Status)}>
                <SelectTrigger className="h-10">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {STATUS_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {/* Deadline */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Дедлайн
            </label>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <div className="relative min-w-0">
                <IconClock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  type="date"
                  value={deadlineDate}
                  onChange={(e) => setDeadlineDate(e.target.value)}
                  className="pl-9 h-10 text-sm w-full"
                />
              </div>
              <Input
                type="time"
                value={deadlineTime}
                onChange={(e) => setDeadlineTime(e.target.value)}
                className="h-10 text-sm w-full"
                disabled={!deadlineDate}
              />
            </div>
          </div>

          {/* Group + Estimate row */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Группа
              </label>
              <Select
                value={selectedGroupId || '__none__'}
                onValueChange={(v) => setSelectedGroupId(v === '__none__' ? '' : v)}
              >
                <SelectTrigger className="h-10">
                  <SelectValue placeholder="Без группы" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">Без группы</SelectItem>
                  {groups.map((g) => (
                    <SelectItem key={g.id} value={g.id}>
                      {g.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Оценка (мин)
              </label>
              <Input
                type="number"
                value={estimatedTime}
                onChange={(e) => setEstimatedTime(e.target.value)}
                placeholder="30"
                className="h-10"
              />
            </div>
          </div>

          {/* Reminders (Б1/Б2) — независимы от срока, могут стоять и на задаче без дедлайна */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Напоминания
            </label>
            {reminders.length > 0 && (
              <div className="space-y-1.5">
                {reminders.map((r) => (
                  <div
                    key={r.id}
                    className="flex items-center justify-between gap-2 rounded-lg border border-border/60 px-3 py-2"
                  >
                    <span className="text-sm flex items-center gap-1.5">
                      <IconBell className="h-3.5 w-3.5 text-muted-foreground" />
                      {formatReminderTime(r.fireAt, timezone)}
                    </span>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 text-muted-foreground hover:text-destructive"
                      onClick={() => taskId && cancelReminder({ taskId, reminderId: r.id })}
                    >
                      <IconX className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                ))}
              </div>
            )}
            {taskId && showAddReminder ? (
              <AddReminderForm taskId={taskId} timezone={timezone} onDone={() => setShowAddReminder(false)} />
            ) : (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-9 gap-1.5"
                onClick={() => setShowAddReminder(true)}
              >
                <IconBellPlus className="h-4 w-4" />
                Напомнить
              </Button>
            )}
          </div>

          {/* Actions */}
          <div className="flex items-center gap-2 pt-1">
            <Button onClick={handleSave} disabled={saving} className="flex-1 h-10">
              {saving ? 'Сохраняем...' : 'Сохранить'}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-10 w-10 text-muted-foreground hover:text-destructive hover:bg-destructive/10 flex-shrink-0"
              onClick={handleDelete}
            >
              <IconTrash className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
