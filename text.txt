Got it ✅ — you’re saying:

* You currently have a **WebSocket-based system** where user replies come through the browser (JS `sendUserReply()`) and are handled in the backend via

  ```python
  async def _user_input(prompt: str, cancellation_token: CancellationToken | None) -> str:
      await websocket.send_json({"type": "UserInputRequestedEvent", "prompt": prompt})
      data = await websocket.receive_json()
      message = TextMessage.model_validate(data)
      return message.content
  ```
* Then you create your user agent like:

  ```python
  user_proxy = UserProxyAgent(name="user_proxy", input_func=_user_input)
  ```
* But now you want to **replace** (or supplement) this with a **FastAPI HTTP endpoint**, so that the user input message can come through a REST API call instead of WebSocket.

---

Here’s how you can do that cleanly 👇

### ✅ Step 1: Use a shared `asyncio.Future` to hold incoming user input

When FastAPI receives the user’s message (via POST), it will complete this `Future`.
The `_user_input()` function will `await` it.

```python
# globals or inside a manager class
import asyncio

user_input_future: asyncio.Future | None = None
```

---

### ✅ Step 2: Modify `_user_input` to wait for REST input

```python
async def _user_input(prompt: str, cancellation_token: CancellationToken | None) -> str:
    global user_input_future

    # Notify front-end or log the prompt
    logging.info(f"Prompt sent to user: {prompt}")

    # Create a Future to wait for API input
    loop = asyncio.get_event_loop()
    user_input_future = loop.create_future()

    # Wait for REST API to set a result
    try:
        user_reply = await user_input_future
        return user_reply
    except asyncio.CancelledError:
        logging.warning("User input was cancelled.")
        raise
```

---

### ✅ Step 3: Create FastAPI endpoint to receive user input

```python
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI()

class UserInputRequest(BaseModel):
    content: str

@app.post("/user-reply")
async def receive_user_reply(data: UserInputRequest):
    global user_input_future
    if user_input_future is None:
        raise HTTPException(status_code=400, detail="No input request pending")

    if user_input_future.done():
        raise HTTPException(status_code=400, detail="Input already received")

    user_input_future.set_result(data.content)
    return {"status": "received", "message": data.content}
```

---

### ✅ Step 4: Use the same agent initialization

```python
user_proxy = UserProxyAgent(name="user_proxy", input_func=_user_input)
```

---

### ✅ Optional: From frontend

You can now call:

```js
await fetch("http://localhost:8000/user-reply", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content: reply }),
});
```

---

### ✅ How it works:

1. Your AI agent calls `_user_input()` → waits for a `Future`.
2. The user sends their message via `/user-reply` API → fulfills that `Future`.
3. `_user_input()` resumes and returns the content to the agent.

---

Would you like me to extend this so it **supports both WebSocket and API input at the same time** (whichever arrives first)?
