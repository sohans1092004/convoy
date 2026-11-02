// The dealer tools are English for now (reports/ui/BACKLOG.md), so the subtree says so for screen readers on Hindi pages.
export default function Layout({ children }: { children: React.ReactNode }) {
  return <div data-density="work" lang="en">{children}</div>;
}
