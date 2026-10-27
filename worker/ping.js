export default {
  async fetch() {
    return new Response('pong', { headers: { 'content-type': 'text/plain' } });
  },
};
