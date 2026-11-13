# M FIT Food AI Worker

This Cloudflare Worker securely sends a food photo to the OpenAI Responses API for visual analysis. The OpenAI API key is stored as a Cloudflare Worker secret and is never shipped to the browser or committed to Git.

## Deploy

1. Create a Cloudflare account and open **Workers & Pages**.
2. Create a Worker named `m-fit-food-ai`.
3. Deploy this folder with Wrangler, or paste `src/index.js` into the Worker editor and deploy.
4. In Worker **Settings → Variables and Secrets**, add:
   - Secret: `OPENAI_API_KEY` = your OpenAI API key.
   - Text variable: `ALLOWED_ORIGIN` = `https://mehranbakhtarani-gif.github.io`.
   - Text variable: `OPENAI_MODEL` = `gpt-6-astra` (change only to a vision-capable model available to your API project).
5. Deploy the Worker again after adding settings. Copy its `workers.dev` URL.

## Local deployment with Wrangler

```bash
npm install -g wrangler
wrangler login
wrangler secret put OPENAI_API_KEY
wrangler deploy
```

Set `ALLOWED_ORIGIN` and `OPENAI_MODEL` in the Cloudflare dashboard before deploying. Never put the API key in `wrangler.toml`, source code, GitHub, or the M FIT browser app.

## API contract

POST JSON to the Worker URL:

```json
{ "imageDataUrl": "data:image/jpeg;base64,..." }
```

Success response:

```json
{
  "analysis": {
    "is_food": true,
    "food_name": "Grilled chicken with rice",
    "portion_grams": 320,
    "calories_kcal": 540,
    "confidence": "medium",
    "notes": "Approximate visual estimate; recipe and hidden oil may change calories."
  }
}
```

The calorie result is an estimate, not a nutrition measurement. The M FIT UI should ask the user to confirm before adding it to the daily total.

## Production hardening

Before public launch, configure Cloudflare rate limiting (and ideally Turnstile or an authenticated session) to prevent anonymous API abuse. CORS is a browser-origin control, not authentication.
