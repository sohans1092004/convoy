import type { Preview } from '@storybook/nextjs-vite';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../messages/en.json';
import { Toaster } from '../components/ui/toast';
import '../app/globals.css';

const preview: Preview = {
  parameters: { a11y: { test: 'error' }, layout: 'padded' },
  decorators: [
    (Story) => (
      <NextIntlClientProvider locale="en" messages={messages} timeZone="Asia/Kolkata">
        <Toaster><Story /></Toaster>
      </NextIntlClientProvider>
    ),
  ],
};
export default preview;
