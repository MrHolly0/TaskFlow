import { useQuery } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

export interface AuthMethods {
  email: boolean;
  telegram: boolean;
  phone: boolean;
}

export const useAuthMethods = () => {
  return useQuery({
    queryKey: ['auth-methods'],
    queryFn: async () => {
      const response = await axios.get<AuthMethods>(`${API_BASE}/auth/methods`);
      return response.data;
    },
  });
};
