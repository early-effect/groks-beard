package groksbeard.core

enum CardSlot derives zio.json.JsonCodec:
  case Permission, Plan, Question, Elicit, Fork
