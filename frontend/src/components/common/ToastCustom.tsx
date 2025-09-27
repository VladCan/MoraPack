// src/components/ui/ToastCustom.tsx
import { toast } from 'react-hot-toast';
import type { Toast } from 'react-hot-toast';
import { X } from 'lucide-react';

interface ToastCustomProps {
  t: Toast;
  message: string;
  type: 'success' | 'error';
}

const ToastCustom = ({ t, message, type }: ToastCustomProps) => {
  const colors =
    type === 'success'
      ? 'backdrop-blur-md bg-green-400/5 border border-green-300/20 text-green-500'
      : 'backdrop-blur-md bg-red-400/5 border border-red-300/20 text-red-500';

  return (
    <div
      className={`${
        t.visible ? 'animate-custom-enter' : 'animate-custom-leave'
      } relative max-w-sm w-full shadow-lg rounded-2xl px-4 py-3 flex items-center justify-between ${colors}`}
    >
      <span className="text-sm font-medium">{message}</span>

      <button
        onClick={() => toast.dismiss(t.id)}
        className="ml-3 text-sm text-black hover:text-red-500 focus:outline-none"
      >
        <X size={16} />
      </button>
    </div>
  );
};

export default ToastCustom;