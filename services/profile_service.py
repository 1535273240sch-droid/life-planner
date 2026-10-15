from sqlalchemy.orm import Session
from models import User, Profile, Skill
from schemas import ProfileUpdate, SkillCreate, SkillUpdate

def get_or_create_user(db: Session, user_id: int = 1) -> User:
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        user = User(id=user_id, nickname="探索者")
        db.add(user)
        db.commit()
        db.refresh(user)
    return user

def get_or_create_profile(db: Session, user_id: int = 1) -> Profile:
    get_or_create_user(db, user_id)
    profile = db.query(Profile).filter(Profile.user_id == user_id).first()
    if not profile:
        profile = Profile(
            user_id=user_id,
            age=None,
            gender="保密",
            occupation="",
            industry="",
            years_of_work=0,
            education="",
            major="",
            health_info={},
            vision="",
            constraints=""
        )
        db.add(profile)
        db.commit()
        db.refresh(profile)
    return profile

def update_profile(db: Session, user_id: int, profile_in: ProfileUpdate) -> Profile:
    profile = get_or_create_profile(db, user_id)
    update_data = profile_in.model_dump(exclude_unset=True)
    for field, value in update_data.items():
        setattr(profile, field, value)
    db.commit()
    db.refresh(profile)
    return profile

def get_skills(db: Session, user_id: int = 1):
    return db.query(Skill).filter(Skill.user_id == user_id).all()

def create_skill(db: Session, user_id: int, skill_in: SkillCreate) -> Skill:
    get_or_create_user(db, user_id)
    skill = Skill(
        user_id=user_id,
        name=skill_in.name,
        category=skill_in.category or "通用",
        level=skill_in.level,
        note=skill_in.note
    )
    db.add(skill)
    db.commit()
    db.refresh(skill)
    return skill

def update_skill(db: Session, skill_id: int, user_id: int, skill_in: SkillUpdate) -> Skill:
    skill = db.query(Skill).filter(Skill.id == skill_id, Skill.user_id == user_id).first()
    if not skill:
        return None
    update_data = skill_in.model_dump(exclude_unset=True)
    for field, value in update_data.items():
        setattr(skill, field, value)
    db.commit()
    db.refresh(skill)
    return skill

def delete_skill(db: Session, skill_id: int, user_id: int) -> bool:
    skill = db.query(Skill).filter(Skill.id == skill_id, Skill.user_id == user_id).first()
    if not skill:
        return False
    db.delete(skill)
    db.commit()
    return True
