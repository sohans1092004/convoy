import { MyCars } from '@/components/my-cars';

export const metadata = { title: 'My cars · Convoy' };

export default function SavedPage() {
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-extrabold">My cars</h1>
      <MyCars />
    </div>
  );
}
