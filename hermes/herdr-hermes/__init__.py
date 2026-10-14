"""Hermes directory-plugin entry: Hermes imports this directory as a package
and calls `register(ctx)`."""
if __package__:
    from .herdr_hermes import register
else:
    # pytest imports this file as a top-level module (the directory name is
    # not an identifier), with the plugin root on sys.path.
    from herdr_hermes import register

__all__ = ["register"]
