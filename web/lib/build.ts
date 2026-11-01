/** The deployed build (server only): short commit SHA and build time from the image, "dev" outside one. */
export function build() {
  return { sha: process.env.GIT_SHA || 'dev', built_at: process.env.BUILD_TIME || null };
}
