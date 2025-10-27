import logging
import os
from datetime import datetime
from typing import Optional

# Autogen imports
from autogen_agentchat.agents import AssistantAgent
from autogen_agentchat.base import TaskResult
from autogen_agentchat.conditions import TextMentionTermination
from autogen_agentchat.teams import RoundRobinGroupChat
from autogen_ext.models.openai import OpenAIChatCompletionClient, AzureOpenAIChatCompletionClient
from dotenv import load_dotenv
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel
from starlette.middleware.cors import CORSMiddleware

from code_run import compile_java_code, run_java_class, compile_and_run_java
from prompts import code_agent_prompt, critic_agent_prompt

LOG_FILE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.log")

load_dotenv()

api_key = os.environ.get("API_KEY")

azure_api_key = os.environ.get("AZURE_OPENAI_API_KEY")
azure_endpoint = os.environ.get("AZURE_OPENAI_ENDPOINT")
api_version = os.environ.get("AZURE_OPENAI_API_VERSION")
APIFY_API_KEY = os.environ.get("APIFY_API_KEY")


description = """
Welcome to the Intelli Compare API! 🚀

This API provides a comprehensive set of functionalities for managing your Intelli Compare platform.
"""


app = FastAPI(
    description=description,
    title="Intelli Compare API",
    version="1.0.0",
    swagger_ui_parameters={
        "syntaxHighlight.theme": "monokai",
        "layout": "BaseLayout",
        "filter": True,
        "tryItOutEnabled": True,
        "onComplete": "Ok"
    },
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# app.include_router(document.router)
# app.include_router(user.router)
# app.include_router(auth.router)
# app.include_router(session.router)
# app.include_router(accounts.router)
# app.include_router(auth.router)

class Code(BaseModel):
    language: str
    code: str
    input: str
    className: str


@app.get("/")
async def root():
    return {"message": "Hello, World!"}


@app.post("/compile")
async def compile(code: Code):
    if code.language == "JAVA":
        return compile_java_code(code.code)
    return {'error': "Currently only java(JAVA) code is available"}



@app.post("/run")
async def run(code:Code):
    if code.language== "JAVA":
        return run_java_class(code.input)
    return {'error':"Currently java(JAVA) code is only available"}



def parse_log_line(line: str):
    try:
        parts = line.split(" ", 3)
        level = parts[0]
        timestamp = " ".join(parts[1:3])
        message = parts[3] if len(parts) > 3 else ""
        dt = datetime.strptime(timestamp, "%Y-%m-%d %H:%M:%S")
        return {"level": level, "timestamp": dt, "message": message, "raw": line}
    except Exception:
        return {"level": "", "timestamp": None, "message": line, "raw": line}



@app.get("/logs", summary="Get server logs")
def get_logs(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD"),
    sort: str = Query("desc", pattern="^(asc|desc)$")
):
    if not os.path.exists(LOG_FILE_PATH):
        return {"total": 0, "logs": []}

    with open(LOG_FILE_PATH, "r", encoding="utf-8") as f:
        lines = f.readlines()

    logs = [parse_log_line(line.strip()) for line in lines if line.strip()]

    if start_date:
        start_dt = datetime.strptime(start_date, "%Y-%m-%d")
        logs = [log for log in logs if log["timestamp"] and log["timestamp"].date() >= start_dt.date()]
    if end_date:
        end_dt = datetime.strptime(end_date, "%Y-%m-%d")
        logs = [log for log in logs if log["timestamp"] and log["timestamp"].date() <= end_dt.date()]

    logs = [log for log in logs if log["timestamp"]]
    logs.sort(key=lambda x: x["timestamp"], reverse=(sort == "desc"))

    total = len(logs)
    start = (page - 1) * page_size
    end = start + page_size
    paginated_logs = logs[start:end]

    return {
        "total": total,
        "page": page,
        "page_size": page_size,
        "logs": [
            {
                "level": log["level"],
                "timestamp": log["timestamp"].strftime("%Y-%m-%d %H:%M:%S") if log["timestamp"] else "",
                "message": log["message"]
            }
            for log in paginated_logs
        ]
    }



@app.websocket("/ws")
async def chat_websocket(websocket: WebSocket):
    await websocket.accept()
    logging.info("WebSocket connection open.")
    try:

        # model_client = AzureOpenAIChatCompletionClient(
        #     azure_deployment="gpt-4.1",
        #     model="gpt-4.1",
        #     api_version=api_version,
        #     azure_endpoint=azure_endpoint,
        #     api_key=azure_api_key
        # )
        # 1. Initialize Model and Receive Initial Problem
        model_client = OpenAIChatCompletionClient(
            model="gemini-2.0-flash",
            api_key=api_key
        )
        initial_data = await websocket.receive_json()
        problem = initial_data.get("problem")

        if not problem:
            await websocket.send_json(
                {"type": "error", "msg": "No 'problem' was provided in the initial message."}
            )
            return

        await websocket.send_json(
            {"type": "sys", "msg": f"Received problem '{problem}'. Assembling agent team for this session..."}
        )

        # 2. Create Session-Specific Agents
        coding_agent = AssistantAgent(
            name="Coding_Agent",
            model_client=model_client,
            tools=[compile_and_run_java],
            system_message=code_agent_prompt,
        )
        critic_agent = AssistantAgent(
            name="Critic_Agent",
            model_client=model_client,
            tools=[compile_and_run_java],
            system_message=critic_agent_prompt,
        )
        logging.info("Session-specific Agents created.")

        # # NOTE: you can skip input by pressing Enter.
        # user_proxy = UserProxyAgent("user_proxy")
        #
        # # Maximum 1 round of review and revision.
        # inner_termination = MaxMessageTermination(max_messages=4)
        #
        # # The outter-loop termination condition that will terminate the team when the user types "exit".
        # outter_termination = TextMentionTermination("exit", sources=["user_proxy"])
        #
        # team = RoundRobinGroupChat(
        #     [
        #         # For each turn, the writer writes a summary and the reviewer reviews it.
        #         RoundRobinGroupChat([writer, reviewer], termination_condition=inner_termination),
        #         # The user proxy gets user input once the writer and reviewer have finished their actions.
        #         user_proxy,
        #     ],
        #     termination_condition=outter_termination,
        # )

        # 3. Set up the Agent Team (without a User Proxy)
        team = RoundRobinGroupChat(
            [coding_agent, critic_agent],
            termination_condition=TextMentionTermination("Approved"),
        )
        await team.reset()

        # 4. Define and Run the Initial Task
        initial_task = (
            f"You are tasked to solve the problem: '{problem}'."
        )

        stream = team.run_stream(task=initial_task)

        # 5. Stream the Conversation Back to the Client
        async for message in stream:
            if isinstance(message, TaskResult):
                continue  # Don't send tool output to the client UI
            await websocket.send_json(message.model_dump(mode="json"))

        await websocket.send_json({"type": "done", "msg": "Team has finished the task."})

    except WebSocketDisconnect:
        logging.info("Client disconnected.")
    except Exception as e:
        logging.error(f"An error occurred in chat_websocket: {e}", exc_info=True)
        # Check if the websocket is still open before sending
        if not websocket.client_state.value == 3:  # 3 is DISCONNECTED state
            await websocket.send_json({"type": "error", "msg": str(e)})
    finally:
        logging.info("WebSocket connection closing.")
        await websocket.close()



@app.get("/chat-ui", summary="Serve the main HTML application for chat")
async def get_chat_ui():
    return FileResponse("index.html")

