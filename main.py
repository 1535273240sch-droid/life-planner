import os
from fastapi import FastAPI, Request, Depends
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session

from config import settings
from database import engine, Base, get_db
import models
from services import profile_service, log_service, goal_service
from routes import api_profile, api_goals, api_tasks, api_logs, api_ai, api_stats

# 初始化数据库表结构
Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="人生智能编排体 (Life Planner AI)",
    description="贴身人生规划师 - 懂你、判断、解释、推动你前进",
    version="1.0.0"
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



# 启动事件：初始化默认用户与档案
@app.on_event("startup")
def on_startup():
    from database import SessionLocal
    db = SessionLocal()
    try:
        profile_service.get_or_create_user(db, user_id=settings.DEFAULT_USER_ID)
        profile_service.get_or_create_profile(db, user_id=settings.DEFAULT_USER_ID)
    finally:
        db.close()

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
    if os.path.exists("templates/advisor.html"):
        return templates.TemplateResponse(request=request, name="advisor.html", context={"active_page": "advisor"})
    return templates.TemplateResponse(request=request, name="index.html", context={"active_page": "advisor"})

@app.get("/review", response_class=HTMLResponse)
async def page_review(request: Request):
    if os.path.exists("templates/review.html"):
        return templates.TemplateResponse(request=request, name="review.html", context={"active_page": "review"})
    return templates.TemplateResponse(request=request, name="index.html", context={"active_page": "review"})

if __name__ == "__main__":

    import uvicorn
    uvicorn.run("main:app", host=settings.APP_HOST, port=settings.APP_PORT, reload=settings.DEBUG)
