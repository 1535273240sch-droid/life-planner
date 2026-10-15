from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List
from database import get_db
from schemas import ProfileOut, ProfileUpdate, SkillOut, SkillCreate, SkillUpdate
from services import profile_service
from config import settings

router = APIRouter(prefix="/api", tags=["个人档案与技能"])

@router.get("/profile", response_model=ProfileOut)
def read_profile(db: Session = Depends(get_db)):
    profile = profile_service.get_or_create_profile(db, user_id=settings.DEFAULT_USER_ID)
    return profile

@router.put("/profile", response_model=ProfileOut)
def update_profile(profile_in: ProfileUpdate, db: Session = Depends(get_db)):
    profile = profile_service.update_profile(db, user_id=settings.DEFAULT_USER_ID, profile_in=profile_in)
    return profile

@router.get("/skills", response_model=List[SkillOut])
def read_skills(db: Session = Depends(get_db)):
    skills = profile_service.get_skills(db, user_id=settings.DEFAULT_USER_ID)
    return skills

@router.post("/skills", response_model=SkillOut)
def create_skill(skill_in: SkillCreate, db: Session = Depends(get_db)):
    skill = profile_service.create_skill(db, user_id=settings.DEFAULT_USER_ID, skill_in=skill_in)
    return skill

@router.put("/skills/{skill_id}", response_model=SkillOut)
def update_skill(skill_id: int, skill_in: SkillUpdate, db: Session = Depends(get_db)):
    skill = profile_service.update_skill(db, skill_id=skill_id, user_id=settings.DEFAULT_USER_ID, skill_in=skill_in)
    if not skill:
        raise HTTPException(status_code=404, detail="技能未找到")
    return skill

@router.delete("/skills/{skill_id}")
def delete_skill(skill_id: int, db: Session = Depends(get_db)):
    success = profile_service.delete_skill(db, skill_id=skill_id, user_id=settings.DEFAULT_USER_ID)
    if not success:
        raise HTTPException(status_code=404, detail="技能未找到")
    return {"message": "技能删除成功"}
