import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { cn } from '@/lib/cn';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 rounded-lg font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-55 min-h-11 px-4 text-[0.95rem]',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-fg hover:brightness-110',
        secondary: 'border border-border bg-surface text-fg hover:bg-surface-2',
        danger: 'bg-danger text-white hover:brightness-110',
        link: 'min-h-6 px-0 text-primary underline underline-offset-4',
      },
      block: { true: 'w-full' },
    },
    defaultVariants: { variant: 'primary' },
  },
);

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
  /** Shows a spinner and announces the busy state; the button stays focusable but inert. */
  loading?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, block, asChild, loading, children, disabled, ...props }, ref) => {
    if (asChild) {
      // Slot needs exactly one child (React counts a null sibling too), so no spinner slot here.
      return <Slot ref={ref} className={cn(buttonVariants({ variant, block }), className)} {...props}>{children}</Slot>;
    }
    return (
      <button ref={ref} className={cn(buttonVariants({ variant, block }), className)} disabled={disabled || loading} aria-busy={loading || undefined} {...props}>
        {loading ? <span aria-hidden className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent" /> : null}
        {children}
      </button>
    );
  },
);
Button.displayName = 'Button';
