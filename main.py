import os
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Depends
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

from config import settings
from database import engine, Base, get_db, migrate_db
import models
from services import profile_service, log_service, goal_service
from routes import api_profile, api_goals, api_tasks, api_logs, api_ai, api_stats

# 初始化数据库表结构与动态增量字段迁移
Base.metadata.create_all(bind=engine)
migrate_db()

# 现代异步生命周期管理器
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动事件：初始化默认用户与档案
    from database import SessionLocal
    db = SessionLocal()
    try:
        profile_service.get_or_create_user(db, user_id=settings.DEFAULT_USER_ID)
        profile_service.get_or_create_profile(db, user_id=settings.DEFAULT_USER_ID)
    finally:
        db.close()
    yield

app = FastAPI(
    title="人生智能编排体 (Life Planner AI)",
    description="贴身人生规划师 - 懂你、判断、解释、推动你前进",
    version="1.0.0",
    lifespan=lifespan
)

# 配置全局跨域 CORS 中间件 (允许原生移动端、局域网及本地 WebView 访问)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 确保目录存在
os.makedirs("static/css", exist_ok=True)
os.makedirs("static/js", exist_ok=True)
os.makedirs("templates", exist_ok=True)

# 挂载静态文件与模板
app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

# 注册 API 路由
app.include_router(api_profile.router)
app.include_router(api_goals.router)
app.include_router(api_tasks.router)
app.include_router(api_logs.router)
app.include_router(api_ai.router)
app.include_router(api_stats.router)

# --- 页面渲染路由 (适配 Starlette 最新 TemplateResponse 规范) ---
@app.get("/", response_class=HTMLResponse)
async def page_index(request: Request):
    return templates.TemplateResponse(request=request, name="index.html", context={"active_page": "index"})

@app.get("/goals", response_class=HTMLResponse)
async def page_goals(request: Request):
    return templates.TemplateResponse(request=request, name="goals.html", context={"active_page": "goals"})

@app.get("/log", response_class=HTMLResponse)
async def page_log(request: Request):
    return templates.TemplateResponse(request=request, name="log.html", context={"active_page": "log"})

@app.get("/profile", response_class=HTMLResponse)
async def page_profile(request: Request):
    return templates.TemplateResponse(request=request, name="profile.html", context={"active_page": "profile"})

@app.get("/advisor", response_class=HTMLResponse)
async def page_advisor(request: Request):
    return templates.TemplateResponse(request=request, name="advisor.html", context={"active_page": "advisor"})

@app.get("/review", response_class=HTMLResponse)
async def page_review(request: Request):
    return templates.TemplateResponse(request=request, name="review.html", context={"active_page": "review"})

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.APP_HOST, port=settings.APP_PORT, reload=settings.DEBUG)
