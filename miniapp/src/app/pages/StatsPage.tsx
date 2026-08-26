import { Card } from '@/app/components/ui/card';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { useTaskStats } from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { isSameZonedDay, zonedDayKey } from '@/lib/utils';

/** Склонение для 2–4 («2 активных дня») и остальных случаев. */
function pluralDays(count: number): string {
  const tail = count % 10;
  const teen = count % 100;
  if (tail === 1 && teen !== 11) return 'активный день';
  if (tail >= 2 && tail <= 4 && (teen < 12 || teen > 14)) return 'активных дня';
  return 'активных дней';
}

export function StatsPage() {
  const { data: statsTasks = [] } = useTaskStats();
  const { timezone, isReady: timezoneReady } = useUserTimezone();

  const now = new Date();

  // Одно окно на всю страницу: последние семь календарных дней в поясе пользователя.
  // Карточки и график считаются по нему же, иначе итоги расходятся со столбцами.
  const windowDays = timezoneReady
    ? Array.from({ length: 7 }, (_, i) => {
        const date = new Date();
        date.setDate(date.getDate() - (6 - i));
        return date;
      })
    : [];

  const windowKeys = new Set(windowDays.map((date) => zonedDayKey(date, timezone)));
  const inWindow = (value?: string | null) =>
    !!value && windowKeys.has(zonedDayKey(value, timezone));

  const created = statsTasks.filter((t) => inWindow(t.createdAt)).length;
  const done = statsTasks.filter((t) => inWindow(t.completedAt)).length;
  const overdue = statsTasks.filter((t) =>
    t.deadline && new Date(t.deadline) < now && t.status !== 'DONE' && t.status !== 'CANCELLED'
  ).length;

  const activityData = windowDays.map((date) => ({
    name: date.toLocaleDateString('ru-RU', { weekday: 'short', timeZone: timezone }),
    создано: statsTasks.filter((task) => isSameZonedDay(task.createdAt, date, timezone)).length,
    сделано: statsTasks.filter(
      (task) => task.completedAt && isSameZonedDay(task.completedAt, date, timezone)
    ).length,
  }));

  const activeDays = activityData.filter((d) => d.создано > 0 || d.сделано > 0).length;

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <h1 className="text-2xl font-semibold">Статистика</h1>

      <div>
        <h2 className="text-sm font-medium text-muted-foreground uppercase tracking-wide mb-3">За 7 дней</h2>
        <div className="grid grid-cols-3 gap-3">
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold">{created}</div>
            <div className="text-xs text-muted-foreground mt-1.5">создано</div>
          </Card>
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{done}</div>
            <div className="text-xs text-muted-foreground mt-1.5">сделано</div>
          </Card>
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold text-red-500">{overdue}</div>
            <div className="text-xs text-muted-foreground mt-1.5">просрочено</div>
          </Card>
        </div>
      </div>

      <Card className="p-5">
        <div className="flex items-center gap-4">
          <div className="text-4xl">🔥</div>
          <div>
            <div className="text-xl font-semibold">{activeDays} {pluralDays(activeDays)}</div>
            <p className="text-sm text-muted-foreground">Отмечено за последние 7 дней</p>
          </div>
        </div>
      </Card>

      <Card className="p-5 space-y-4">
        <h2 className="text-sm font-medium">Активность по дням</h2>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={activityData} barCategoryGap="30%">
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              dataKey="name"
              tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
              stroke="var(--border)"
            />
            <YAxis
              tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
              stroke="var(--border)"
              width={24}
              allowDecimals={false}
              domain={[0, (max: number) => Math.max(max, 4)]}
            />
            <Tooltip
              cursor={{ fill: 'var(--accent)', opacity: 0.5 }}
              contentStyle={{
                backgroundColor: 'var(--card)',
                color: 'var(--card-foreground)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                fontSize: '12px',
              }}
            />
            <Legend
              wrapperStyle={{ fontSize: '12px', color: 'var(--muted-foreground)' }}
            />
            <Bar dataKey="создано" fill="var(--chart-created)" radius={[4, 4, 0, 0]} />
            <Bar dataKey="сделано" fill="var(--primary)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}
