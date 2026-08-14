import { useMemo, useState } from 'react';
import { IconCheck, IconSelector } from '@tabler/icons-react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/app/components/ui/popover';
import {
  Command,
  CommandEmpty,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/app/components/ui/command';
import {
  detectTimezone,
  listTimezones,
  timezoneLabel,
  timezoneSearchText,
} from '@/lib/timezones';
import { cn } from '@/lib/utils';

type Props = {
  value: string;
  onChange: (timezone: string) => void;
  disabled?: boolean;
  id?: string;
};

export function TimezonePicker({ value, onChange, disabled, id }: Props) {
  const [open, setOpen] = useState(false);

  // Список из четырёхсот зон и подписи со смещением считаются один раз:
  // пересборка на каждый ввод символа заметно тормозила бы поиск.
  const zones = useMemo<{ zone: string; label: string; search: string }[]>(
    () =>
      listTimezones().map((zone) => ({
        zone,
        label: timezoneLabel(zone),
        search: timezoneSearchText(zone),
      })),
    [],
  );

  // Пояс устройства выносится первым пунктом, а не отдельной ссылкой рядом:
  // большинству он и нужен, а искать свой город в четырёхстах строках —
  // занятие для тех, у кого он с поясом не совпадает.
  const detected = useMemo(() => detectTimezone(), []);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      {/* Триггер стилизуется напрямую, без asChild с Button: Button здесь —
          обычная функция без forwardRef, а на React 18 ref в такой компонент
          не попадает. Radix не может привязаться к элементу, и всплывающий
          список просто не открывается. Тем же способом сделан SelectTrigger. */}
      <PopoverTrigger
        id={id}
        role="combobox"
        aria-expanded={open}
        disabled={disabled}
        className="border-input flex h-10 w-full items-center justify-between gap-2 rounded-md border bg-input-background px-3 py-2 text-sm outline-none transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30"
      >
        <span className="truncate">
          {value ? timezoneLabel(value) : 'Выбери часовой пояс'}
        </span>
        <IconSelector className="h-4 w-4 shrink-0 opacity-50" />
      </PopoverTrigger>
      <PopoverContent className="w-(--radix-popover-trigger-width) max-w-[calc(100vw-2rem)]">
        <Command>
          <CommandInput placeholder="Город или регион…" />
          <CommandList>
            <CommandEmpty>Ничего не нашлось</CommandEmpty>
            {detected && (
              <CommandItem
                value="определить автоматически auto"
                onSelect={() => {
                  onChange(detected);
                  setOpen(false);
                }}
              >
                <IconCheck className="h-4 w-4 shrink-0 opacity-0" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate">Определить автоматически</span>
                  <span className="text-muted-foreground block truncate text-xs">
                    {timezoneLabel(detected)}
                  </span>
                </span>
              </CommandItem>
            )}
            {zones.map(({ zone, label, search }) => (
              <CommandItem
                key={zone}
                value={search}
                onSelect={() => {
                  onChange(zone);
                  setOpen(false);
                }}
              >
                <IconCheck
                  className={cn(
                    'h-4 w-4 shrink-0',
                    zone === value ? 'opacity-100' : 'opacity-0',
                  )}
                />
                <span className="truncate">{label}</span>
              </CommandItem>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
