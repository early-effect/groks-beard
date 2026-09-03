package groksbeard.ui

import ascent.ast.Attr
import ascent.domtypes.AttrValue

object TestId:
  def apply(id: String): Attr[Any] =
    Attr.StaticAttr("data-testid", AttrValue.Str(id))
