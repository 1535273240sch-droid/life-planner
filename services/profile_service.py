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
            age=28,
            gender="保密",
            occupation="软件工程师",
            industry="人工智能/互联网",
            years_of_work=4,
            education="本科",
            major="计算机科学与技术",
            health_info={
                "height": 175,
                "weight": 70,
                "sleep_habit": "23:30睡, 07:30起",
                "diet_pref": "清淡少油, 蛋白质优先",
                "taboo": "无",
                "allergy_or_disease": "轻度花粉过敏, 长期伏案偶有颈椎酸痛"
            },
            vision="在未来3-5年内成为全栈智能体领域的顶尖实践者，兼顾健康强健的体魄与自律从容的生活方式。",
            constraints="每天工作外有3小时可用学习与运动时间；重视长期复利，拒绝高透支低收益的事情。"
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
