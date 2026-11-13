const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);

function json(data, status, origin) {
  const headers = new Headers(JSON_HEADERS);
  headers.set("cache-control", "no-store");
  if (origin) {
    headers.set("access-control-allow-origin", origin);
    headers.set("vary", "Origin");
  }
  return new Response(JSON.stringify(data), { status, headers });
}

function cors(request, env) {
  const origin = request.headers.get("Origin");
  const allowed = env.ALLOWED_ORIGIN;
  if (!origin || !allowed || origin !== allowed) return null;
  return origin;
}

export default {
  async fetch(request, env) {
    const origin = cors(request, env);
    if (request.method === "OPTIONS") {
      if (!origin) return new Response(null, { status: 403 });
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": origin,
          "access-control-allow-methods": "POST, OPTIONS",
          "access-control-allow-headers": "content-type",
          "access-control-max-age": "86400",
          "vary": "Origin"
        }
      });
    }
    if (request.method !== "POST") return json({ error: "Method not allowed" }, 405, origin);
    if (!origin) return json({ error: "Origin not allowed" }, 403);
    if (!env.OPENAI_API_KEY) return json({ error: "AI service is not configured yet." }, 503, origin);

    try {
      const body = await request.json();
      const imageDataUrl = body?.imageDataUrl;
      if (typeof imageDataUrl !== "string") return json({ error: "Missing image." }, 400, origin);
      const match = imageDataUrl.match(/^data:(image\/(?:jpeg|png|webp|gif));base64,([A-Za-z0-9+/=]+)$/);
      if (!match) return json({ error: "Unsupported image format." }, 400, origin);
      const mimeType = match[1];
      const base64 = match[2];
      const estimatedBytes = Math.floor(base64.length * 3 / 4);
      if (!ALLOWED_TYPES.has(mimeType) || estimatedBytes > MAX_IMAGE_BYTES) {
        return json({ error: "Image must be JPG, PNG, WEBP or GIF and under 8 MB." }, 413, origin);
      }

      const upstream = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "authorization": `Bearer ${env.OPENAI_API_KEY}`,
          "content-type": "application/json"
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-6-astra",
          input: [{
            role: "user",
            content: [
              {
                type: "input_text",
                text: "Analyze this food photo for a food diary. Identify the most likely dish and visible components. Estimate the edible portion in grams and total calories (kcal). If multiple foods are visible, estimate each separately and provide a total. Be conservative and clearly state uncertainty; a photo cannot reliably reveal hidden ingredients, exact weight, oil, sauces, or recipe. If the image is not food or cannot be assessed, set is_food=false. Return ONLY valid JSON with this exact shape: { \"is_food\": boolean, \"food_name\": string, \"portion_grams\": number|null, \"calories_kcal\": number|null, \"confidence\": \"low\"| \"medium\"| \"high\", \"notes\": string }."
              },
              {
                type: "input_image",
                image_url: imageDataUrl,
                detail: "high"
              }
            ]
          }]
        })
      });

      const result = await upstream.json();
      if (!upstream.ok) {
        return json({ error: "Food analysis provider returned an error.", provider_status: upstream.status }, 502, origin);
      }
      const textOutput = (result.output || [])
        .flatMap(item => item.content || [])
        .find(item => item.type === "output_text")?.text;
      if (!textOutput) return json({ error: "No analysis was returned." }, 502, origin);

      let analysis;
      try {
        const cleaned = textOutput.replace(/^\s*```(?:json)?\s*|\s*```\s*$/g, "");
        analysis = JSON.parse(cleaned);
      } catch {
        return json({ error: "The AI response could not be parsed. Please try another photo." }, 502, origin);
      }
      if (typeof analysis.is_food !== "boolean") return json({ error: "Invalid analysis result." }, 502, origin);
      if (analysis.is_food && (!Number.isFinite(analysis.calories_kcal) || analysis.calories_kcal < 0)) {
        return json({ error: "The AI could not estimate calories reliably." }, 422, origin);
      }
      return json({ analysis }, 200, origin);
    } catch {
      return json({ error: "Could not process this photo. Please try again." }, 400, origin);
    }
  }
};
