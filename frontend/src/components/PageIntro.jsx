export default function PageIntro({ eyebrow, title, children }) {
  return <section className="page-heading compact-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{children}</p></div></section>
}
