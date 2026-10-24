import * as React from 'react';
import { cn } from '@/lib/cn';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  hint?: string;
  error?: string;
}

/** Labelled input; the error is linked with aria-describedby and the value is never cleared on error. */
export const Input = React.forwardRef<HTMLInputElement, InputProps>(({ label, hint, error, id, className, ...props }, ref) => {
  const autoId = React.useId();
  const inputId = id ?? autoId;
  const describedBy = [hint ? `${inputId}-hint` : null, error ? `${inputId}-error` : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-semibold">{label}</label>
      {hint ? <p id={`${inputId}-hint`} className="text-sm text-muted">{hint}</p> : null}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn('min-h-11 rounded-lg border border-border bg-surface px-3 text-base text-fg placeholder:text-muted', error && 'border-danger', className)}
        {...props}
      />
      {error ? <p id={`${inputId}-error`} role="alert" className="text-sm font-medium text-danger">{error}</p> : null}
    </div>
  );
});
Input.displayName = 'Input';
