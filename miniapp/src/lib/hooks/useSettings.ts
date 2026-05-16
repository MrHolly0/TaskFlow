import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

const getClient = () => {
  const token = localStorage.getItem('auth_token');
  return axios.create({
    baseURL: API_BASE,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
};

export interface UserSettings {
  notificationsEnabled: boolean;
  defaultReminderMinutes: number;
  urgentExtraReminder: boolean;
  preferredLlm: string;
  autoCleanCompletedDays: number | null;
}

interface UpdateSettingsRequest {
  autoCleanCompletedDays?: number | null;
  notificationsEnabled?: boolean;
  defaultReminderMinutes?: number;
  urgentExtraReminder?: boolean;
  preferredLlm?: string;
}

export const useSettings = () => {
  return useQuery({
    queryKey: ['settings'],
    queryFn: async () => {
      const response = await getClient().get<UserSettings>('/settings');
      return response.data;
    },
    staleTime: 1000 * 60 * 5,
  });
};

export const useUpdateSettings = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: UpdateSettingsRequest) => {
      const response = await getClient().put<UserSettings>('/settings', request);
      return response.data;
    },
    onSuccess: (data) => {
      queryClient.setQueryData(['settings'], data);
    },
  });
};

export const useClearCompleted = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const response = await getClient().delete<{ cleared: number }>('/tasks/completed');
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};
